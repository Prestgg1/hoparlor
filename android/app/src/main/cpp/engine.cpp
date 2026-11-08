// SPDX-License-Identifier: GPL-3.0-or-later
#include "engine.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <ctime>

#define LOG_TAG "HoparlorEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace hoparlor {
namespace {

constexpr uint8_t kMagic[4] = {'H', 'P', 'R', '1'};
constexpr uint8_t kTypeAudio = 1;
constexpr uint8_t kTypeHello = 2;
constexpr uint8_t kTypeBye = 4;
constexpr uint8_t kTypeHelloAck = 5;
constexpr size_t kAudioHeader = 16;

constexpr int64_t kHelloIntervalNs = 20'000'000;  // frequent uplink keeps Wi-Fi out of power save
constexpr int64_t kGlitchWindowNs = 1'000'000'000;   // a gap longer than this is "server idle"
constexpr int64_t kAdaptDecayNs = 20'000'000'000;    // shrink target after 20 s without glitches
constexpr int kAdaptStepFrames = Engine::kRate * 4 / 1000;   // +4 ms per glitch
constexpr int kDecayStepFrames = Engine::kRate / 1000;       // -1 ms per decay step
constexpr int kMaxTargetFrames = Engine::kRate * 200 / 1000;
constexpr int kMaxLossFill = 16;  // packets of concealment before we resync instead

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return int64_t(ts.tv_sec) * 1'000'000'000 + ts.tv_nsec;
}

uint16_t rd16(const uint8_t* p) { return uint16_t(p[0] | (p[1] << 8)); }
uint32_t rd32(const uint8_t* p) { return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24); }

void putHeader(uint8_t* p, uint8_t type) {
    memcpy(p, kMagic, 4);
    p[4] = type;
    p[5] = p[6] = p[7] = 0;
}

}  // namespace

bool Engine::start(const std::string& host, int port, int targetMs, bool adaptive) {
    stop();

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(uint16_t(port));
    if (inet_pton(AF_INET, host.c_str(), &addr.sin_addr) != 1) {
        LOGW("invalid address %s", host.c_str());
        return false;
    }
    sock_ = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (sock_ < 0) return false;
    // DSCP EF: Wi-Fi WMM maps this to the voice access category (lowest latency queue).
    int tos = 0xB8;
    setsockopt(sock_, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));
    if (connect(sock_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        LOGW("connect failed: %s", strerror(errno));
        close(sock_);
        sock_ = -1;
        return false;
    }

    // Reset all stream state.
    writePos_.store(0);
    readPos_.store(0);
    playing_ = false;
    phase_ = 0.0;
    ratio_ = 1.0;
    haveSeq_ = false;
    lastPacketFrames_ = 0;
    underruns_ = 0;
    packets_ = 0;
    lost_ = 0;
    lastPacketNs_ = 0;
    packetFrames_ = 0;
    rttMs_ = -1.f;
    underrunFlag_ = false;
    setTarget(targetMs, adaptive);
    targetFrames_ = minTargetFrames_.load();

    if (!openStream()) {
        close(sock_);
        sock_ = -1;
        return false;
    }
    running_ = true;
    netThread_ = std::thread(&Engine::networkLoop, this);
    return true;
}

void Engine::stop() {
    if (running_.exchange(false)) {
        if (netThread_.joinable()) netThread_.join();
    }
    if (restartThread_.joinable()) restartThread_.join();
    closeStream();
    if (sock_ >= 0) {
        close(sock_);
        sock_ = -1;
    }
}

void Engine::setTarget(int targetMs, bool adaptive) {
    int frames = std::clamp(targetMs, 1, 200) * kRate / 1000;
    minTargetFrames_ = frames;
    adaptive_ = adaptive;
    if (!adaptive || targetFrames_.load() < frames) targetFrames_ = frames;
}

// ---------------------------------------------------------------- network --

void Engine::networkLoop() {
    // Same priority class Android gives audio threads.
    setpriority(PRIO_PROCESS, 0, -19);

    uint8_t buf[2048];
    int64_t nextHello = 0;
    while (running_.load(std::memory_order_relaxed)) {
        int64_t now = nowNs();
        if (now >= nextHello) {
            uint8_t hello[16];
            putHeader(hello, kTypeHello);
            memcpy(hello + 8, &now, 8);
            send(sock_, hello, sizeof(hello), MSG_DONTWAIT);
            nextHello = now + kHelloIntervalNs;
        }

        pollfd pfd{sock_, POLLIN, 0};
        if (poll(&pfd, 1, 100) <= 0) continue;

        ssize_t n = recv(sock_, buf, sizeof(buf), 0);
        if (n < 8 || memcmp(buf, kMagic, 4) != 0) continue;
        now = nowNs();
        switch (buf[4]) {
            case kTypeAudio:
                handleAudio(buf, size_t(n), now);
                break;
            case kTypeHelloAck:
                if (n >= 16) {
                    int64_t sent;
                    memcpy(&sent, buf + 8, 8);
                    float rtt = float(now - sent) / 1e6f;
                    float prev = rttMs_.load();
                    rttMs_ = prev < 0 ? rtt : prev * 0.8f + rtt * 0.2f;
                }
                break;
            default:
                break;
        }
    }

    uint8_t bye[8];
    putHeader(bye, kTypeBye);
    send(sock_, bye, sizeof(bye), MSG_DONTWAIT);
}

void Engine::handleAudio(const uint8_t* pkt, size_t len, int64_t now) {
    if (len < kAudioHeader) return;
    int channels = pkt[5];
    int frames = rd16(pkt + 6);
    uint32_t seq = rd32(pkt + 8);
    if (channels < 1 || channels > 8 || frames <= 0) return;
    if (len < kAudioHeader + size_t(frames) * channels * 2) return;
    const auto* pcm = reinterpret_cast<const int16_t*>(pkt + kAudioHeader);

    // Adaptive jitter buffer: if the consumer ran dry and packets are still flowing
    // (gap < 1 s, so it was not just the PC going silent), raise the target.
    int64_t prev = lastPacketNs_.load(std::memory_order_relaxed);
    if (underrunFlag_.exchange(false) && adaptive_.load() && prev != 0 && now - prev < kGlitchWindowNs) {
        int t = std::min(targetFrames_.load() + kAdaptStepFrames, kMaxTargetFrames);
        targetFrames_ = t;
        lastAdaptNs_ = now;
        LOGI("glitch: target -> %d ms", t * 1000 / kRate);
    } else if (adaptive_.load() && now - lastAdaptNs_ > kAdaptDecayNs) {
        int t = std::max(targetFrames_.load() - kDecayStepFrames, minTargetFrames_.load());
        targetFrames_ = t;
        lastAdaptNs_ = now;
    }
    // Arrival jitter over a one-second window.
    if (prev != 0 && now - prev < kGlitchWindowNs) maxGapNs_ = std::max(maxGapNs_, now - prev);
    ++windowPackets_;
    if (now - windowStartNs_ >= 1'000'000'000) {
        float nominal = float(frames) * 1000.f / kRate;
        float j = std::max(0.f, float(maxGapNs_) / 1e6f - nominal);
        jitterMs_.store(j, std::memory_order_relaxed);
        LOGI("arrival: %d pkt/s, max gap %.1f ms, jitter %.1f ms, target %d ms", windowPackets_,
             float(maxGapNs_) / 1e6f, j, targetFrames_.load() * 1000 / kRate);
        windowStartNs_ = now;
        maxGapNs_ = 0;
        windowPackets_ = 0;
    }
    lastPacketNs_.store(now, std::memory_order_relaxed);
    packets_.fetch_add(1, std::memory_order_relaxed);
    packetFrames_.store(frames, std::memory_order_relaxed);

    if (haveSeq_) {
        int32_t delta = int32_t(seq - expectedSeq_);
        if (delta < 0 && delta > -64) return;  // late or duplicate: already concealed
        if (delta > 0 && delta <= kMaxLossFill) {
            lost_.fetch_add(uint32_t(delta), std::memory_order_relaxed);
            // Conceal by repeating the previous packet at reduced level.
            for (int i = 0; i < delta; ++i) {
                if (lastPacketFrames_ > 0) {
                    std::vector<int16_t> soft(lastPacket_.size());
                    for (size_t k = 0; k < soft.size(); ++k) soft[k] = int16_t(lastPacket_[k] / 2);
                    writeFrames(soft.data(), lastPacketFrames_, lastPacketChannels_);
                } else {
                    writeSilence(frames);
                }
            }
        }
        // delta beyond the window: server restarted or a long outage, just resync.
    }
    haveSeq_ = true;
    expectedSeq_ = seq + 1;

    writeFrames(pcm, frames, channels);
    lastPacket_.assign(pcm, pcm + size_t(frames) * channels);
    lastPacketFrames_ = frames;
    lastPacketChannels_ = channels;
}

void Engine::writeFrames(const int16_t* pcm, int frames, int channels) {
    uint64_t w = writePos_.load(std::memory_order_relaxed);
    uint64_t r = readPos_.load(std::memory_order_acquire);
    if (w - r + uint64_t(frames) > uint64_t(kRingFrames)) return;  // consumer stalled; drop
    constexpr float kScale = 1.0f / 32768.0f;
    for (int i = 0; i < frames; ++i) {
        float* dst = &ring_[((w + i) & (kRingFrames - 1)) * kChannels];
        const int16_t* src = pcm + size_t(i) * channels;
        if (channels == 1) {
            dst[0] = dst[1] = src[0] * kScale;
        } else {
            dst[0] = src[0] * kScale;
            dst[1] = src[1] * kScale;
        }
    }
    writePos_.store(w + frames, std::memory_order_release);
}

void Engine::writeSilence(int frames) {
    uint64_t w = writePos_.load(std::memory_order_relaxed);
    uint64_t r = readPos_.load(std::memory_order_acquire);
    if (w - r + uint64_t(frames) > uint64_t(kRingFrames)) return;
    for (int i = 0; i < frames; ++i) {
        float* dst = &ring_[((w + i) & (kRingFrames - 1)) * kChannels];
        dst[0] = dst[1] = 0.f;
    }
    writePos_.store(w + frames, std::memory_order_release);
}

// ------------------------------------------------------------------ audio --

bool Engine::openStream() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, kChannels);
    AAudioStreamBuilder_setSampleRate(b, kRate);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(b, &Engine::dataCallback, this);
    AAudioStreamBuilder_setErrorCallback(b, &Engine::errorCallback, this);

    aaudio_result_t res = AAudioStreamBuilder_openStream(b, &stream_);
    if (res != AAUDIO_OK) {
        // Some devices refuse EXCLUSIVE; fall back to shared low-latency.
        AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        res = AAudioStreamBuilder_openStream(b, &stream_);
    }
    AAudioStreamBuilder_delete(b);
    if (res != AAUDIO_OK) {
        LOGW("openStream failed: %s", AAudio_convertResultToText(res));
        stream_ = nullptr;
        return false;
    }

    // Two bursts of device buffering is the usual minimum that stays glitch-free.
    int32_t burst = AAudioStream_getFramesPerBurst(stream_);
    AAudioStream_setBufferSizeInFrames(stream_, burst * 2);

    LOGI("AAudio: rate=%d burst=%d buffer=%d sharing=%s", AAudioStream_getSampleRate(stream_), burst,
         AAudioStream_getBufferSizeInFrames(stream_),
         AAudioStream_getSharingMode(stream_) == AAUDIO_SHARING_MODE_EXCLUSIVE ? "exclusive" : "shared");

    res = AAudioStream_requestStart(stream_);
    if (res != AAUDIO_OK) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
        return false;
    }
    return true;
}

void Engine::closeStream() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_) {
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

void Engine::errorCallback(AAudioStream*, void* user, aaudio_result_t error) {
    auto* self = static_cast<Engine*>(user);
    LOGW("AAudio error: %s", AAudio_convertResultToText(error));
    if (error != AAUDIO_ERROR_DISCONNECTED || !self->running_) return;
    // The output device changed (headphones, Bluetooth...). Reopen off the callback thread.
    if (self->restartThread_.joinable()) self->restartThread_.detach();
    self->restartThread_ = std::thread([self] {
        self->closeStream();
        if (self->running_) self->openStream();
    });
}

aaudio_data_callback_result_t Engine::dataCallback(AAudioStream*, void* user, void* audio, int32_t frames) {
    static_cast<Engine*>(user)->render(static_cast<float*>(audio), frames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void Engine::render(float* out, int32_t frames) {
    uint64_t r = readPos_.load(std::memory_order_relaxed);
    uint64_t w = writePos_.load(std::memory_order_acquire);
    int64_t fill = int64_t(w - r);
    const int target = targetFrames_.load(std::memory_order_relaxed);

    if (!playing_) {
        if (fill < target) {
            std::fill(out, out + frames * kChannels, 0.f);
            return;
        }
        // Start from exactly the target depth to begin with minimal latency.
        r = w - uint64_t(target);
        fill = target;
        playing_ = true;
        phase_ = 0.0;
        emaFill_ = fill;
    }

    // Hard latency bound: after a burst (e.g. Wi-Fi stall then flush), jump back to target.
    const int ceiling = target + std::max(target, kRate * 25 / 1000);
    if (fill > ceiling) {
        r = w - uint64_t(target);
        fill = target;
        emaFill_ = fill;
    }

    // Clock drift compensation: nudge the playback rate (max ±0.5%, inaudible) so the
    // buffer depth hovers around the target instead of slowly draining or growing.
    double alpha = std::min(1.0, double(frames) / (kRate * 0.5));
    emaFill_ += (double(fill) - emaFill_) * alpha;
    double err = emaFill_ - double(target);
    ratio_ = 1.0 + std::clamp(err * 2e-5, -0.005, 0.005);
    ratioPpm_.store(float((ratio_ - 1.0) * 1e6), std::memory_order_relaxed);

    const float* ring = ring_.data();
    constexpr uint64_t mask = kRingFrames - 1;
    int32_t i = 0;
    for (; i < frames; ++i) {
        if (int64_t(w - r) < 2) break;
        const float* a = ring + (r & mask) * kChannels;
        const float* b = ring + ((r + 1) & mask) * kChannels;
        float f = float(phase_);
        out[i * 2] = a[0] + (b[0] - a[0]) * f;
        out[i * 2 + 1] = a[1] + (b[1] - a[1]) * f;
        phase_ += ratio_;
        while (phase_ >= 1.0) {
            phase_ -= 1.0;
            ++r;
        }
    }
    if (i < frames) {
        std::fill(out + i * kChannels, out + frames * kChannels, 0.f);
        playing_ = false;
        underruns_.fetch_add(1, std::memory_order_relaxed);
        underrunFlag_.store(true, std::memory_order_relaxed);
    }
    readPos_.store(r, std::memory_order_release);
}

// ------------------------------------------------------------------ stats --

void Engine::stats(float* s) {
    std::fill(s, s + kStatCount, 0.f);
    const float msPerFrame = 1000.f / kRate;
    uint64_t w = writePos_.load(), r = readPos_.load();
    s[kStatBufferMs] = float(w - r) * msPerFrame;
    s[kStatTargetMs] = float(targetFrames_.load()) * msPerFrame;
    float rtt = rttMs_.load();
    s[kStatNetworkMs] = rtt < 0 ? 0.f : rtt / 2.f;
    s[kStatPackets] = float(packets_.load());
    s[kStatLost] = float(lost_.load());
    s[kStatUnderruns] = float(underruns_.load());
    s[kStatRatio] = ratioPpm_.load();
    s[kStatPacketMs] = float(packetFrames_.load()) * msPerFrame;
    s[kStatJitterMs] = jitterMs_.load();
    int64_t last = lastPacketNs_.load();
    s[kStatReceiving] = (last != 0 && nowNs() - last < 500'000'000) ? 1.f : 0.f;

    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_) {
        s[kStatExclusive] = AAudioStream_getSharingMode(stream_) == AAUDIO_SHARING_MODE_EXCLUSIVE ? 1.f : 0.f;
        s[kStatBurst] = float(AAudioStream_getFramesPerBurst(stream_));
        int64_t pos = 0, timeNs = 0;
        if (AAudioStream_getTimestamp(stream_, CLOCK_MONOTONIC, &pos, &timeNs) == AAUDIO_OK) {
            int64_t written = AAudioStream_getFramesWritten(stream_);
            int64_t presentNs = timeNs + (written - pos) * 1'000'000'000LL / kRate;
            s[kStatOutputMs] = std::max(0.f, float(presentNs - nowNs()) / 1e6f);
        }
    }
    s[kStatTotalMs] = s[kStatPacketMs] + s[kStatNetworkMs] + s[kStatBufferMs] + s[kStatOutputMs];
}

}  // namespace hoparlor
