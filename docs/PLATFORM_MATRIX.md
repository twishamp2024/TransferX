# Platform capability and limitation matrix

Legend: **Verified** = built and exercised in this environment. **Emulator** = exercised on an Android 15 (API 35) x86_64 emulator only, not a physical phone. **Built, not run** = compiles and packages, never executed on that OS or device. **Not implemented** = no code exists.

| | Windows | macOS | Linux | Android | iOS |
|---|---|---|---|---|---|
| App builds | **Verified** (native `TransferX.exe` + bundled runtime) | Not built (needs a Mac for `.dmg`) | Not built (needs Linux for `.deb`/`.rpm`) | **Verified** (debug APK, minSdk 26 / targetSdk 35) | **Not implemented** |
| App runs | **Verified** (two instances, GUI transfer) | Not tested | Not tested | **Emulator** (launch, UI, kill + relaunch; no crashes) | — |
| Wi‑Fi/LAN transfer (TCP + TLS) | **Verified** | Shared JVM code, untested | Shared JVM code, untested | **Emulator**: both directions with the host, resume after process kill | — |
| Discovery (multicast + broadcast) | **Verified** (between processes on one PC) | Untested | Untested | Untested (the emulator NAT blocks multicast) | — |
| QR / link / address + code fallback | **Verified** (link pasted in GUI; address + code in CLI) | Untested | Untested | **Emulator**: address + code both directions; `transferx://` deep-link intent opens prefilled (camera scan itself not tested) | — |
| Bluetooth | **Not implemented** (no JVM Bluetooth API) | Not implemented | Not implemented | **Implemented, untested** with a peer: RFCOMM with system-paired phones; the TLS-over-stream path is tested on JVM. The UI correctly reports the missing permission | Not implemented |
| File/folder picking | **Verified** (native dialog, drag and drop) | Untested | Untested | **Emulator**: SAF multi-select of documents; folder trees untested | — |
| Background transfers | Runs while the app is open | — | — | **Emulator**: the foreground service starts and stops with transfers (started from the foreground); background-start untested | — |

## Why these limits exist

- **iOS:** the shared engine runs on the JVM, which iOS can't run, and Xcode/macOS isn't available here. An iOS client needs its own implementation of [PROTOCOL.md](PROTOCOL.md) (Network.framework + CryptoKit, or a Kotlin/Native port of `core`). Third-party iOS apps can't use Bluetooth Classic RFCOMM (MFi only). BLE L2CAP channels would be the iOS option.
- **Desktop Bluetooth:** Java has no Bluetooth API. It would need native code per OS: WinRT RFCOMM on Windows, IOBluetooth on macOS, BlueZ on Linux. Alternative: put both devices on the same Wi‑Fi or a phone hotspot.
- **Android Bluetooth:** secure RFCOMM needs the phones paired in system Bluetooth settings first. Expect well under 1 MB/s. TransferX labels it "best for small files".
- **Android receive folder:** received data is written with file APIs, which are needed for atomic rename and fsync'd checkpoints. The choices are `Download/TransferX` and the app's private folder. Arbitrary SAF folders aren't supported as destinations.
- **Android 12+ background start:** if a transfer *starts* while TransferX is in the background, the OS may refuse to start the foreground service. The transfer still runs, but the process can be killed. Keep the receiving app open when a transfer begins.
- **Wi‑Fi Direct / local-only hotspot:** not implemented. Use the system hotspot instead.
- **Different networks:** devices on different Wi‑Fi networks or subnets can't connect. TransferX reports `DIFFERENT_NETWORK` rather than appearing to connect.
- **FAT32 destinations:** files of 4 GiB or more are flagged in preflight and can't be accepted there.
