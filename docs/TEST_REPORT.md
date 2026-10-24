# Test report

Environment: Windows 11 Home (10.0.26300), Intel Core i3-8145U (4 threads, no SHA extensions), Temurin JDK 21.0.12, Gradle 8.11.1, Kotlin 2.1.0, AGP 8.7.3. One laptop, plus an Android 15 emulator added later (section 2b). **No physical phones, tablets, Macs, or Linux machines were available.**

## 1. Automated tests: `./gradlew :core:test`

**Result: 62 tests, 62 passed, 0 failed, 0 skipped** (run on 2026-10-24 after the power-outage recovery; 2 min 12 s).

The JVM test heap is capped at 256 MiB, so any whole-file buffering would show up as `OutOfMemoryError`.

Scenario tests run two or more complete TransferX engines in one JVM, talking over **real TCP + mutual TLS on loopback**. "Phone" tests are **simulated**: they set the platform identity, use path-less stream sources (like Android `content://` URIs), and use smaller chunks. They didn't run on Android.

### Required scenarios

| # | Scenario | Test | Result |
|---|---|---|---|
| 1 | Small file | `01 small file transfer succeeds` | Pass |
| 2 | Large file, bounded memory | `02 large file…`: 1 GiB with production settings | Pass: 31.3 s, peak heap 62 MiB of 256 MiB |
| 3 | Multiple files | `03 multiple files and folders…` (incl. empty file, nested folders) | Pass |
| 4 | Phone → phone | `04` (simulated android → android) | Pass (simulated) |
| 5 | Phone → laptop | `05` (simulated android → windows) | Pass (simulated) |
| 6 | Laptop → laptop | `06` (windows → linux identities) | Pass (simulated platforms) |
| 7 | Laptop → phone | `07` (windows → simulated android) | Pass (simulated) |
| 8 | Receiver rejects | `08` | Pass |
| 9 | Sender cancels | `09` (completed first file kept, no partial left) | Pass |
| 10 | Connection drop | `10`: proxy cuts the link at 3 MB of 10 MB; automatic reconnect + resume | Pass: 10,424,499 bytes forwarded in total (no full re-send) |
| 11 | Resume from verified checkpoint | `11`: manual resume after interruption | Pass: 3,670,016 of 8,000,000 bytes kept; 4,350,631 resent |
| 12 | Insufficient space before transfer | `12` (quota storage; only fitting file accepted) | Pass |
| 13 | Storage runs out mid-transfer | `13`: partial truncated to exactly the checkpoint; no final file | Pass |
| 14 | Storage recovered → resume | `14`, `14b` (resume in place, and in another folder) | Pass |
| 15 | Completed file intact when another fails | `15` | Pass |
| 16 | Duplicate file name | `16`: rename, skip, and explicit replace | Pass |
| 17 | Corruption fails integrity | `17`, `17b`, `17c` (on-disk byte flip; retry or fail) | Pass |
| 18 | Unauthorized device can't receive | `18` impostor with stolen name/id, `18b` wrong codes, `18e` resume hijack by another paired device; plus `18c`/`18d` positive pairing | Pass |
| 19 | Invalid file names can't escape | `19`: `../`, absolute, drive-letter paths refused; `CON.txt`, `a<b>:c?` sanitized | Pass |
| 20 | Termination never marks incomplete files complete | `20`: receiver engine killed mid-transfer, restarted from disk, recovered, resumed | Pass (engine shutdown, not an OS process kill) |
| 21 | Permission revoked | `21` (destination write denied → blocked → restored → completes), `21b` (source revoked) | Pass |
| 22 | Discovery fails → fallback offered | `22`: fallback suggested; manual address on another network gives `DIFFERENT_NETWORK` | Pass |
| 23 | Concurrent transfers | `23`: two senders, same file name, both intact | Pass |
| 24 | Cancel while awaiting approval | `24` | Pass |

Additional passing tests cover:
- framing limits and malformed frames
- pairing-code expiry, rotation, and lockout; MAC binding
- sanitizer rules, preflight arithmetic, the FAT32 limit, I/O error classification
- checkpoint reload, truncation, and damaged checkpoints
- rate limiting, local-address filtering, speed meter
- the TLS-over-`SSLEngine` adapter used for Bluetooth, plus a full transfer through it
- approval timeout, not-accepting, pause/resume from both sides, receiver single-file cancel
- partial data altered between sessions, source modified mid-transfer, history persistence and retry
- discovery between two services on one machine (`22b`; passed in this run)

## 2. Manual end-to-end runs (real processes, same laptop)

All runs used **two separate OS processes with separate identities and data folders** on one PC, connecting to the Wi‑Fi interface address `192.168.29.42`. Both ends were on the same machine, so traffic didn't cross a router or the air. **This isn't a test between two physical devices.**

Received files were checked with the system `sha256sum`, independently of TransferX's own verification.

| Run | Path | Result |
|---|---|---|
| CLI → CLI | Pairing by code, receiver auto-accept; 150 MiB file + folder with nested file (158.1 MB) | Completed. Hashes match. `.transferx-partial` removed. Source mtime preserved. Peak ~37 MB/s (power state not recorded) |
| Desktop GUI → desktop GUI | Native file dialog (3 files, 86.2 MB) → discovered device → pairing dialog (code read from receiver window) → incoming-transfer dialog with storage check → accept | Completed. Hashes match. ~3–4 MB/s on battery |
| Desktop GUI → CLI | "Enter address or link" with the receiver's `transferx://` link (fingerprint + code, no typing) | Completed, 83.9 MB, ~10 MB/s. Hash matches |
| CLI → desktop GUI | Code read from the GUI Receive screen; existing name → "Keep both" | Completed; saved as `report (1).pdf`, `holiday-video (1).mp4`, `(2)` |
| CLI → CLI (control) | Same as row 1, repeated on battery | Completed; ramped 2.7 → 12.4 MB/s |

**Throughput note:** speeds varied ~10× between runs. The control rerun showed this was the machine's power state (on battery, Balanced plan) and CPU contention, because both endpoints share one 4-thread CPU. It wasn't the GUI. Expect higher numbers between two separate devices; on Wi‑Fi the radio link is usually the limit. No cross-device benchmark was run.

Discovery was observed working between processes: the GUI listed the other GUI and the CLI receiver, and duplicate names were disambiguated by fingerprint.

### Defects found by manual testing and fixed

1. `settings.json` saved with a UTF‑8 BOM was ignored. The loader now accepts a BOM.
2. CLI `receive --exit-after N` never exited, because a collector coroutine kept `runBlocking` alive.
3. Network-interface enumeration (100–270 ms with 40 adapters here) ran on the UI thread. Moved to IO.
4. The incoming-offer expiry countdown started before the prompt was built. It now starts when the prompt is shown. Measured preflight: 34–90 ms.
5. Declined offers showed "0 B" in history.

Defects found by automated tests and fixed:
- `select` bias starved user commands during transfers.
- TCP RST on close discarded the final cancel message (fixed with drain-before-close).
- History was persisted after the in-memory state was published.

## 2b. Android emulator runs (2026-10-24)

The emulator was an AVD `TransferX_API35`: Android 15 (API 35), AOSP `default` x86_64 image rev 2, emulator 37.2.12, WHPX acceleration, host GPU, 2 GB guest RAM. **It's an emulator, not a physical phone.**

Host ↔ emulator traffic goes through the emulator's NAT:
- host → app through `adb forward tcp:48020 tcp:47820`
- app → host through `10.0.2.2`

Hashes were checked with `sha256sum` on both sides. The app's crash buffer (`logcat -b crash`) was empty for the whole session.

| Check | Result |
|---|---|
| `adb install` of `androidApp-debug.apk`, cold launch | Works. 8.2–11.2 s to first frame on the emulator |
| First-run setup | Device identity created on Android (EC key, BouncyCastle cert, PKCS12), TLS listener on 47820, notification permission prompt shown and granted |
| Default receive folder | `Download/TransferX` created and used (Android 15) |
| Host → Android (CLI sender, code read from the phone's Receive screen) | **Completed**: 2 files, 36.5 MB. Hashes match. Partial folder removed. mtimes preserved. Atomic rename into `Download/` works under scoped storage |
| Android → host (system picker multi-select → "Enter address or link" `10.0.2.2:47860` + code) | **Completed**: 2 files, 45.4 MB, streamed from `content://` URIs. Hashes match |
| Incoming-transfer dialog on the phone | Sender, fingerprint, destination, storage preflight (9.9 GB available measured), per-file checkboxes, Accept |
| Pairing code | Single-use: rotated after a successful pairing |
| Received files in the media index | Yes: MediaScanner registered them (they appear in the system picker's Recent list) |
| Foreground service | Started when a transfer began (app in foreground), stopped when it ended |
| `transferx://connect?…` deep link (as from a scanned QR) | Opens the Connect dialog prefilled with the link |
| **App process killed mid-transfer** (`am force-stop` at 36% of 315 MB) | No final file. Partial held exactly 128 MiB (16 checkpoints). The host gave up after 3 reconnects and reported INTERRUPTED. After relaunch, `transferx-cli resume` completed; SHA‑256 matches; temp data cleaned up |
| History after the kill | All 3 sessions present and Completed |

Defects found on the emulator and fixed:
- The Receive screen on phone widths squeezed the address and pairing code out of view. It's now stacked, and label/value rows wrap.
- CLI `send`/`resume` would never exit after an interruption, because it waited for `reconnectAttempt == 0`.

Observed and not fixed:
- The device identity is generated on the main thread in `Application.onCreate`, which adds to cold start on first launch.
- Partial files inside `Download/TransferX/.transferx-partial` make MediaProvider log "Database update failed while renaming" for checkpoint temp files. Transfers are unaffected.
- A kill can leave a `*.tmp` from an interrupted atomic write. It's removed with the session folder.

Not covered on the emulator:
- discovery (multicast doesn't cross the emulator NAT)
- Bluetooth (no peer; permission never granted)
- "Add folder"
- share-sheet intake
- storage-full on Android
- pause/cancel from the phone UI
- starting a transfer while the app is in the background (Android 12+ foreground-service restrictions)
- physical devices

## 3. Builds

| Target | Command | Result |
|---|---|---|
| Core + tests | `./gradlew :core:test` | Pass (62/62) |
| CLI | `./gradlew :cli:installDist` | Built and used in the runs above |
| Windows desktop | `./gradlew :desktopApp:createDistributable` | Built `TransferX.exe` + runtime; ran two instances |
| Android | `./gradlew :androidApp:assembleDebug` | Built `androidApp-debug.apk` (13,454,873 bytes after the layout fix), minSdk 26 / targetSdk 35, debug-key signature verified. **Installed and run on an API 35 emulator** (section 2b); not run on a physical device |
| MSI/EXE/DMG/DEB/RPM installers, Android release | — | Not built |
| macOS, Linux, iOS | — | Not built (no such machines; iOS not implemented) |

## 4. Not tested

- Any physical phone or tablet. Android was tested only on an emulator.
- Real Bluetooth: the Android RFCOMM code never ran with a peer. Only the TLS-over-stream path is tested, over TCP.
- Two physical computers over real Wi‑Fi.
- Discovery through a real router, guest networks, or client isolation.
- macOS and Linux behavior.
- A real out-of-space disk: storage exhaustion is tested with a quota-limited storage layer.
- Killing the OS process (test 20 shuts the engine down in-process).
- Android background-start limits. The foreground service was only seen starting while the app was in the foreground.
