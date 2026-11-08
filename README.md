# Hoparlor

Use your Android phone as a low-latency speaker for your Linux PC, over Wi-Fi or a USB cable.
A free and open-source alternative to SoundWire and AudioRelay.

*Azərbaycanca: [aşağıda](#azərbaycanca).*

- **Low latency.** Uncompressed audio in 2.5–5 ms packets, a native AAudio player and an adaptive jitter buffer. About 40–50 ms end to end over USB on a mid-range phone; most of that is the phone's own audio output.
- **No setup.** The phone finds your PC by itself and connects automatically. While it is connected, the PC's sound moves to the phone, and moves back when you disconnect.
- **USB or Wi-Fi.** With USB tethering on, Hoparlor picks the cable automatically, since it is much steadier than Wi-Fi.
- **Stays in sync.** Clock drift between PC and phone is corrected continuously, so the delay does not creep up over time.
- **Private.** No accounts, ads, analytics or internet access. Audio never leaves your local network.

## How it works

```
 apps ──► PipeWire ──► "Hoparlor" sink ──► hoparlor-server ──UDP──► phone ──► jitter buffer ──► AAudio ──► speaker
                                            (PC, Rust)              (C++)       + drift correction
```

The PC side creates a virtual output device in PipeWire and sends what plays there as raw 48 kHz/16-bit
stereo PCM over UDP. The phone keeps a small buffer that grows only if the network glitches,
and slightly speeds up or slows down playback (±0.5 %, inaudible) to follow the PC's clock.
See [PROTOCOL.md](PROTOCOL.md) for the wire format.

## Usage

1. Install the app on your phone (F-Droid, or the APK from Releases).
2. Run `hoparlor-server` on your PC.
3. Open the app. Your PC shows up under *Computers on this network*, and the app connects by itself.

For the lowest and steadiest delay, connect the phone with a USB cable and turn on
**Settings → Network → Hotspot & tethering → USB tethering**.

### Server options

```
hoparlor-server [OPTIONS]
  -p, --port <PORT>      UDP port phones connect to [default: 47800]
  -f, --frames <FRAMES>  Frames per packet: 120 = 2.5 ms, 240 = 5 ms [default: 240]
  -m, --mirror           Copy whatever the current output plays instead of creating a new device
  -n, --name <NAME>      Name shown on the phone (default: hostname)
      --no-switch        Don't make Hoparlor the default output while a phone is connected
      --no-discovery     Don't announce the server on the local network
  -v, --verbose          Log network timing (useful for diagnosing Wi-Fi problems)
```

The server needs UDP ports **47800** (audio) and **47801** (discovery) open in your firewall.

### Start the server automatically

```sh
install -Dm755 server/target/release/hoparlor-server ~/.local/bin/hoparlor-server
install -Dm644 server/dist/hoparlor-server.service ~/.config/systemd/user/hoparlor-server.service
systemctl --user enable --now hoparlor-server
```

If you install the binary elsewhere, change `ExecStart` in the service file to match.

## Building

**Server** (Linux with PipeWire). Needs Rust, `clang` and the PipeWire headers
(`libpipewire-0.3` / `pipewire-devel` / `libpipewire-0.3-dev`):

```sh
cd server
cargo build --release
```

**Android app.** Needs the Android SDK and NDK 28.2.13676358:

```sh
cd android
./gradlew assembleRelease                         # all ABIs
./gradlew assembleRelease -Phoparlor.abis=arm64-v8a  # one ABI
```

### Reproducible release build

F-Droid builds Hoparlor from source and publishes it under **our** signing key, which only
works if its build is byte-for-byte identical to ours. Build releases exactly like F-Droid:

```sh
git clone https://github.com/Prestgg1/hoparlor.git && cd hoparlor
git checkout v0.1.0                       # a clean tree at the tagged commit
export JAVA_HOME=/path/to/jdk-21          # F-Droid builds with JDK 21
git clone https://gitlab.com/fdroid/reproducible-apk-tools.git ../reproducible-apk-tools

cd android/app                            # F-Droid's `subdir`
for abi in armeabi-v7a arm64-v8a x86_64; do
  ../gradlew -q clean assembleRelease -Phoparlor.abis=$abi
  python3 ../../../reproducible-apk-tools/zipalign.py --page-size 16 --pad-like-apksigner \
      build/outputs/apk/release/app-release-unsigned.apk hoparlor-0.1.0-$abi.apk
  apksigner sign --ks <release.keystore> --v1-signing-enabled false hoparlor-0.1.0-$abi.apk
done
```

| Must match | Why |
| --- | --- |
| **JDK 21** | another JDK produces a slightly different `classes.dex` |
| **Clean tree at the tagged commit** | AGP embeds the git state in `META-INF/version-control-info.textproto` |
| **NDK 28.2.13676358** | the native library must compile identically |
| **The `zipalign.py` call** | F-Droid applies the same alignment in its postbuild step |

Without `android/keystore.properties` the release build stays unsigned, which is what F-Droid needs.

## Troubleshooting

- **The PC does not show up.** Both devices must be on the same network (or connected by USB with tethering on), and the firewall must allow UDP 47800–47801. Some public or guest Wi-Fi networks block devices from seeing each other; use **Connect manually** with the PC's IP address, or a USB cable.
- **Crackling on Wi-Fi.** Use 5 GHz Wi-Fi, keep the adaptive buffer on, or raise the buffer. Some Wi-Fi adapters pause for 100–200 ms when they save power or scan for networks. A USB cable avoids this completely.
- **Sound stays on the phone after the server crashed.** Pick your normal output again in the sound settings, or run `pactl set-default-sink <your-sink>`.

## Status

Early but working. Linux (PipeWire) only for now; Windows and macOS servers may follow.

## License

[GPL-3.0-or-later](LICENSE).

---

## Azərbaycanca

Hoparlor Android telefonunuzu Linux kompüteriniz üçün aşağı gecikməli dinamikə çevirir. Wi-Fi və ya USB kabel ilə işləyir. SoundWire və AudioRelay-in pulsuz, açıq mənbəli alternatividir.

1. Telefona tətbiqi quraşdırın (F-Droid və ya Releases bölməsindən APK).
2. Kompüterdə `hoparlor-server` proqramını işə salın.
3. Tətbiqi açın. Kompüter avtomatik tapılır və qoşulur. Qoşulu olduqda kompüterin səsi telefona keçir, ayrılanda isə geri qayıdır.

Ən az və ən stabil gecikmə üçün telefonu USB kabellə qoşun və **Parametrlər → Şəbəkə → Hotspot və modem → USB modem (tethering)** seçimini aktiv edin.
