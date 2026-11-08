// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <aaudio/AAudio.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace hoparlor {

// Indices into the stats array exported to Kotlin.
enum Stat : int {
    kStatBufferMs = 0,     // audio currently queued in the jitter buffer
    kStatTargetMs,         // jitter buffer target (adapts upward on glitches)
    kStatOutputMs,         // AAudio output latency (DAC side)
    kStatNetworkMs,        // half of the measured round trip
    kStatTotalMs,          // estimated end-to-end latency
    kStatPackets,          // packets received
    kStatLost,             // packets missing (sequence gaps)
    kStatUnderruns,        // times the buffer ran dry while playing
    kStatExclusive,        // 1 if AAudio granted EXCLUSIVE (MMAP) mode
    kStatBurst,            // AAudio frames per burst
    kStatRatio,            // drift-correction playback ratio, in ppm offset
    kStatReceiving,        // 1 if a packet arrived in the last 500 ms
    kStatPacketMs,         // duration of one network packet (sender-side buffering)
    kStatJitterMs,         // worst packet inter-arrival gap beyond nominal, last second
    kStatCount
};

class Engine {
public:
    static constexpr int kRate = 48000;
    static constexpr int kChannels = 2;
    static constexpr int kRingFrames = 1 << 14;  // ~341 ms at 48 kHz

    bool start(const std::string& host, int port, int targetMs, bool adaptive);
    void stop();
    void setTarget(int targetMs, bool adaptive);
    void stats(float* out);

private:
    // Network thread
    void networkLoop();
    void handleAudio(const uint8_t* pkt, size_t len, int64_t nowNs);
    void writeFrames(const int16_t* pcm, int frames, int channels);
    void writeSilence(int frames);

    // Audio output
    bool openStream();
    void closeStream();
    static aaudio_data_callback_result_t dataCallback(AAudioStream*, void* user, void* audio, int32_t frames);
    static void errorCallback(AAudioStream*, void* user, aaudio_result_t error);
    void render(float* out, int32_t frames);

    // Jitter ring: single producer (network thread), single consumer (audio callback).
    std::vector<float> ring_ = std::vector<float>(kRingFrames * kChannels);
    std::atomic<uint64_t> writePos_{0};
    std::atomic<uint64_t> readPos_{0};

    // Consumer-only state
    bool playing_ = false;
    double phase_ = 0.0;
    double emaFill_ = 0.0;
    double ratio_ = 1.0;

    // Shared state
    std::atomic<int> targetFrames_{960};
    std::atomic<int> minTargetFrames_{960};
    std::atomic<bool> adaptive_{true};
    std::atomic<bool> underrunFlag_{false};
    std::atomic<uint32_t> underruns_{0};
    std::atomic<uint32_t> packets_{0};
    std::atomic<uint32_t> lost_{0};
    std::atomic<int64_t> lastPacketNs_{0};
    std::atomic<float> rttMs_{-1.f};
    std::atomic<float> ratioPpm_{0.f};
    std::atomic<int> packetFrames_{0};
    std::atomic<float> jitterMs_{0.f};

    // Producer-only state
    bool haveSeq_ = false;
    uint32_t expectedSeq_ = 0;
    int64_t lastAdaptNs_ = 0;
    int64_t windowStartNs_ = 0;
    int64_t maxGapNs_ = 0;
    int windowPackets_ = 0;
    std::vector<int16_t> lastPacket_;
    int lastPacketFrames_ = 0;
    int lastPacketChannels_ = 0;

    int sock_ = -1;
    std::atomic<bool> running_{false};
    std::thread netThread_;

    std::mutex streamMutex_;
    AAudioStream* stream_ = nullptr;
    std::thread restartThread_;
};

}  // namespace hoparlor
