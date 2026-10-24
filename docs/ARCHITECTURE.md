# TransferX architecture

## 1. Technology decision

| Option | Platforms from one codebase | Native API access | Buildable/testable in this environment | Verdict |
|---|---|---|---|---|
| **Kotlin (JVM) core + Compose UI** | Android, Windows, macOS, Linux (iOS needs a separate platform layer) | Android SDK directly; JDK networking/TLS/NIO on desktop | Yes: JDK 21 + Android SDK present | **Chosen** |
| Flutter | All five | Via platform channels/FFI | SDK not installed; iOS still needs macOS | Strong alternative, not verifiable here |
| Electron + React Native | All five, two UI stacks | Node on desktop, native modules on mobile | Partially | Two runtimes, two protocol implementations |
| .NET MAUI | Android, iOS, Windows, macOS (no Linux) | Good | .NET SDK absent | Missing Linux |

**Why Kotlin/JVM.** Android and desktop JVMs share the same `java.net`, `javax.net.ssl`, `java.nio.file`, and `java.security` APIs. That lets **one transfer engine** (protocol, TLS, storage, resume, integrity) run unchanged on Android, Windows, macOS, and Linux. The whole engine is unit-tested on the JVM on this machine. The UI is Jetpack Compose on Android and Compose for Desktop on desktop. Both compile the same shared UI source folder.

**Trade-off.** iOS can't run JVM bytecode. An iOS client would need its own implementation of the documented protocol ([PROTOCOL.md](PROTOCOL.md)) using Network.framework and CryptoKit, or a Kotlin/Native port of the core. That isn't implemented. See [PLATFORM_MATRIX.md](PLATFORM_MATRIX.md).

**Why the UI is shared by source folder, not a KMP module.** A Kotlin Multiplatform module whose `commonMain` depends on the JVM-only core needs extra intermediate source sets and metadata compilation. Compiling the same Compose source folder into both apps avoids that build complexity. Every Compose API it uses exists in both Jetpack Compose and Compose for Desktop.

## 2. Modules

```
core/         Pure Kotlin/JVM library (no UI, no Android APIs). Runs on Android + desktop.
  protocol/     Framing, messages, error codes
  security/     Device identity (self-signed X.509, EC P-256), TLS contexts, TLS-over-stream, pairing codes, trust store
  discovery/    UDP multicast + broadcast discovery, device registry, network utilities
  storage/      Storage backend abstraction, preflight, filename sanitizer, I/O error classification
  transfer/     Sender/receiver session state machines, checkpoints, manifests, engine (connection mgmt)
  persistence/  Transfer history, receive-session index
  util/         Hashing, atomic file writes, logging (redacting), ids, speed meter
cli/          Command-line front end (headless send/receive/discover) — used for scripting and E2E verification
shared-ui/    Compose UI source shared by desktopApp and androidApp (screens, AppController)
desktopApp/   Compose for Desktop app (Windows/macOS/Linux): file dialogs, paths, entry point
androidApp/   Android app: SAF file picking, foreground service, multicast lock, Bluetooth RFCOMM, permissions
docs/         Architecture, protocol, platform matrix, test report
```

The component boundaries from the requirements map like this:

| Component | Location |
|---|---|
| User interface | `shared-ui`, `desktopApp`, `androidApp` |
| Platform integration | `desktopApp/Platform*`, `androidApp/*` (pickers, service, Bluetooth, permissions) |
| Device discovery | `core/discovery` |
| Connection management | `core/transfer/TransferEngine` (listener, limits, rate limiting, reconnect) |
| Pairing and authentication | `core/security` (`PairingCodes`, `TrustStore`, `Tls`) and the handshake in `TransferEngine` |
| Transfer protocol | `core/protocol` and `core/transfer/{SenderSession,ReceiverSession}` |
| File streaming | `core/transfer/FileSource`, chunked I/O in sessions |
| Storage management | `core/storage` |
| Persistence and recovery | `core/persistence`, `core/transfer/{ReceiveManifest,Checkpoint}` |
| Integrity verification | Per-chunk SHA-256, prefix digests, whole-file SHA-256 re-read from disk |
| Logging and diagnostics | `core/util/Log` (ring buffer, never logs file contents or pairing codes) |
| Automated tests | `core/src/test` |

## 3. Key decisions

1. **TLS 1.3/1.2 with mutual certificates, pinned at the application layer.** Each device generates a self-signed EC P-256 certificate on first run. Its identity is the SHA-256 fingerprint of that certificate. The TLS trust manager accepts any certificate *during* the handshake, then the engine immediately compares the peer fingerprint with the trust store or the fingerprint expected from a QR code. No application data flows before that check. No custom cryptography is used. BouncyCastle is used only to *build* the X.509 certificate; the JDK and Android don't expose a public API for that.
2. **Pairing with a short-lived 60-bit code.** A receiver shows a 12-character Crockford base32 code (5-minute TTL, 5 attempts, rotated after use). Both sides prove knowledge of the code with HMAC-SHA256 over both TLS certificate fingerprints. That binds the code to *this* TLS channel and defeats a relaying man-in-the-middle. The QR code also carries the receiver's fingerprint, which pins it before pairing starts.
3. **Receiver consent on every transfer**, including from paired devices.
4. **Chunked streaming with per-chunk SHA-256**, a bounded in-flight window, and receiver acknowledgements only after `fsync` plus a persisted checkpoint.
5. **Resume is verified, not assumed.** On resume the receiver re-hashes its partial file against the stored chunk hashes and truncates at the first bad chunk. The sender recomputes the same chunk hashes over its source prefix and compares digests. On mismatch, that file restarts and the user is told why.
6. **Success means a finalized, verified file.** The receiver re-reads the whole partial file from disk and recomputes the file digest. The digest is SHA-256 over the per-chunk SHA-256 hashes, a two-level hash list (see [PROTOCOL.md](PROTOCOL.md#integrity)). The receiver compares it with the sender's value and moves the file into place with an atomic move. The sender marks a file complete only when it receives the receiver's `file_result: COMPLETED`.

   *Why a hash list and not plain SHA-256 of the file:* profiling on the development machine (Intel i3-8145U, no SHA extensions) measured Java SHA-256 at ~107 MiB/s, and the first design hashed every byte four times. With the hash list, the sender hashes each byte once. The 1 GiB loopback test went from 60 s to 43 s. The trade-off: the digest doesn't equal `sha256sum` output.
7. **Partials live in `<destination>/.transferx-partial/<session>/`.** That's the same volume as the destination, so the final move is an atomic rename. Partials never carry the final filename, so they can't be mistaken for completed files.
8. **Storage is checked three times:** a preflight before accepting, a check before each file starts, and handling of `ENOSPC`/quota errors on every write. Writes roll back to the last durable checkpoint. The connection stays open while the user frees space or picks another folder.
9. **Discovery doesn't depend on multicast.** Discovery sends multicast *and* directed broadcast. The fallback is "address + pairing code" and a QR code, which works whenever TCP unicast works.
10. **No cloud, accounts, analytics, or ads.** Nothing leaves the local network.
11. **Blocking I/O on dedicated coroutines (Dispatchers.IO).** The UI observes `StateFlow` snapshots. Progress publishing is throttled. Status changes are published immediately.

## 4. Limitations accepted by design

- Local-network trust is TOFU-plus-code. If a user types a code shown by an attacker's device, they've paired with the attacker. See the security notes in the README.
- Any device on the LAN can see the device name and fingerprint in discovery announcements. Turn off "Discoverable" in Settings on untrusted networks.
- The available-space measurement is advisory. The OS can consume space at any time, so mid-transfer exhaustion is handled as a normal, recoverable state.
