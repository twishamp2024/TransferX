# TransferX

Direct, encrypted file transfer between phones and computers on the same local network. There's no cloud, account, or internet connection involved. The receiver approves every transfer. Each file is checked against the sender's digest before it's shown as received. Transfers that hit a full disk or a dropped connection pause safely and resume from the last verified point.

**Status (see [docs/TEST_REPORT.md](docs/TEST_REPORT.md)):**
- Windows desktop app and CLI: built and verified, including a GUI-to-GUI transfer.
- Android: the APK builds and has been verified on an Android 15 emulator (transfers both ways, resume after the app was killed), but not on a physical phone.
- macOS/Linux: not built or tested.
- iOS: **not implemented**.

Platform details are in [docs/PLATFORM_MATRIX.md](docs/PLATFORM_MATRIX.md).

## Requirements

- JDK 17 or newer (verified with Temurin 21). The Gradle wrapper downloads Gradle 8.11.1 and all dependencies.
- **Android only:** an Android SDK, with its location in `local.properties` (`sdk.dir=C:/path/to/Android/Sdk`) or `ANDROID_HOME`. If no SDK is configured, the `androidApp` module is left out of the build. AGP installs SDK Platform 35 and Build-Tools 34.0.0 automatically when the SDK licence is accepted.

## Build and run

Commands use the Gradle wrapper (`gradlew.bat` on Windows, `./gradlew` elsewhere). All were run successfully on Windows 11.

```bash
./gradlew :core:test
```

Runs the 62 automated tests (protocol, storage, recovery, security, end-to-end over TLS).

```bash
./gradlew :desktopApp:createDistributable
```

Builds the desktop app with a bundled Java runtime, for the OS you're on. On Windows it's at `desktopApp/build/compose/binaries/main/app/TransferX/TransferX.exe`. Options: `--home <dir>` (data directory) and `--name <device name>`. To run two instances on one machine, give each its own `--home`. If port 47820 is taken, the second instance picks a free port.

```bash
./gradlew :cli:installDist
```

Builds the command-line client at `cli/build/install/transferx-cli/bin/transferx-cli` (`.bat` on Windows). Examples:

```bash
transferx-cli receive --dest ./incoming
transferx-cli send --to 192.168.1.20:47820 --code ABCD-EFGH-JKMN photo.jpg Documents/
transferx-cli discover
```

`receive` prints the address, pairing code, and `transferx://` link, and asks before accepting each offer (`--yes` accepts files that fit). Other commands: `info`, `history`, and `resume <session>`. `resume` was used to finish a transfer after the receiving Android app was killed mid-transfer.

```bash
./gradlew :androidApp:assembleDebug
```

Builds `androidApp/build/outputs/apk/debug/androidApp-debug.apk`, signed with the debug key. These commands were used on the emulator: install the APK, then forward a host port to the app so the host CLI can reach it through the emulator's NAT. From inside the emulator, the host is `10.0.2.2`.

```bash
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

```bash
adb forward tcp:48020 tcp:47820
```

`packageMsi`, `packageDmg`, `packageDeb`, and an Android release build are configured but haven't been built or verified. A release APK needs your own signing config.

## Using it

1. On the receiving device, open **Receive**. It shows a QR code, the address, and a single-use pairing code (valid 5 minutes).
2. On the sending device, choose files or folders and pick the receiver under **Nearby devices**. If discovery is blocked, use **Enter address or link**. On Android, scanning the QR code with the camera app opens TransferX with the details filled in.
3. The first time two devices connect, the sender types the code shown on the receiver. Paired devices skip this step.
4. The receiver sees the sender, the files, and a free-space check, then accepts (all files or some) or rejects.
5. Both sides show measured progress. Files appear under their final names only after verification.

Data folders:
- Windows: `%APPDATA%\TransferX`
- macOS: `~/Library/Application Support/TransferX`
- Linux: `~/.local/share/transferx`
- Override with `TRANSFERX_HOME` or `--home`.

Received files go to `Downloads/TransferX` by default (changeable in Settings). Logs, which never include file contents or pairing codes, are in `<data>/logs/transferx.log`.

**Firewall:** TransferX listens on TCP 47820 and UDP 47821. For other devices to connect, the OS firewall must allow it on *private* networks. Windows normally prompts on first launch. This wasn't observed in testing, because all test runs were on a single machine.

## Security notes

- Connections use TLS 1.3/1.2 with a per-device certificate. Devices are identified by certificate fingerprint and pinned after pairing.
- Pairing proves both sides know the code and are talking over *this* TLS connection, so a relaying attacker on the network can't pair.
- Connections from non-local (public) addresses are refused. Incoming connections and pairing attempts are rate-limited.
- **Limits:** anyone on the same network can see a visible device's name. If you type a code shown on an attacker's screen, you pair with the attacker, so only enter codes from devices you can see. Turn off "Visible to nearby devices" on public Wi‑Fi. Local data, including the identity key, relies on OS user-account protection.

## Project layout

| Path | Contents |
|---|---|
| `core/` | Transfer engine (Kotlin/JVM, shared by desktop and Android): protocol, TLS and pairing, discovery, storage preflight, checkpoints, resume, integrity |
| `shared-ui/` | Compose UI compiled into both apps |
| `desktopApp/` | Windows/macOS/Linux shell: file dialogs, drag and drop, packaging |
| `androidApp/` | Android shell: SAF pickers, foreground service, multicast lock, Bluetooth RFCOMM, deep links |
| `cli/` | Headless client |
| `docs/` | [Architecture](docs/ARCHITECTURE.md), [protocol](docs/PROTOCOL.md), [platform matrix](docs/PLATFORM_MATRIX.md), [test report](docs/TEST_REPORT.md) |

## Known limitations and next steps

- **Android on real phones:** emulator testing covered transfers both ways, the deep link, and resume after the app was killed. Still untested: discovery on real Wi‑Fi, a camera QR scan, Bluetooth between two paired phones, transfers started in the background, folder picking, and Android 8–10 (legacy storage paths).
- **Android polish:** move identity generation off the main thread (it slows first launch). Consider a `.nomedia` marker in the partial folder: MediaProvider logs warnings while indexing checkpoint temp files.
- **Two physical computers on real Wi‑Fi:** all manual runs so far used two processes on one laptop.
- **iOS:** implement the protocol natively (Network.framework + CryptoKit) on a Mac with Xcode.
- **Desktop Bluetooth:** needs native per-OS bindings. Wi‑Fi Direct and Android's local-only hotspot aren't implemented; use a system hotspot.
- **Packaging:** build and sign installers (MSI/DMG/DEB) and a release APK; test on macOS and Linux.
- The file digest is SHA-256 over per-chunk SHA-256 hashes, so it doesn't match `sha256sum` output (see [docs/PROTOCOL.md](docs/PROTOCOL.md#integrity)).
