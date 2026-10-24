# TransferX protocol, version 1

The protocol runs over a mutually authenticated TLS 1.3 connection, with TLS 1.2 as fallback. Wi‑Fi uses TCP on port 47820 by default, falling back to an ephemeral port if 47820 is taken. Bluetooth runs TLS over an RFCOMM stream through `SSLEngine`. The source of truth is `core/src/main/kotlin/com/transferx/core/protocol/` and `core/.../transfer/`.

## Framing

```
frame   := u32 length | u8 type | payload      (big-endian; length = 1 + payload size)
CONTROL := type 0x01, payload = UTF-8 JSON object with a "type" discriminator
DATA    := type 0x02, payload = i32 fileIndex | i64 offset | 32-byte SHA-256(chunk) | chunk bytes
```

Limits: control frames are at most 8 MiB, and data frames at most one chunk of up to 4 MiB. A larger length is rejected *before* any allocation. Unknown JSON fields are ignored for forward compatibility. Malformed JSON is a `PROTOCOL_ERROR`.

## Identity and authentication

- Each installation generates an EC P‑256 key and a self-signed X.509 certificate on first run. Its **fingerprint** is SHA-256 of the certificate (DER encoding).
- Both sides present certificates. The TLS trust manager only checks that one is present. Right after the handshake, the engine compares the peer fingerprint with the pinned value: from a QR code, a discovery announcement, or the trust store entry for that device id. On mismatch: `IDENTITY_MISMATCH`, and no file metadata is sent.
- **Pairing** happens when either side doesn't yet trust the other:

```
C → S  hello                       S → C  hello
S → C  auth_status{peerTrusted}
C → S  pair_request                S → C  pair_challenge{expiresInMs, attemptsRemaining}
                                    (receiver UI shows the 12-character code)
C → S  pair_proof{mac = HMAC(code, "TransferX-pair-v1"‖0‖"CLIENT"‖0‖fpClient‖fpServer)}
S → C  pair_result{ok, mac = HMAC(code, …"SERVER"…)}       client verifies the server MAC too
```

Codes are 60 bits (Crockford base32), live 5 minutes, are single-use, and are invalidated after 5 wrong proofs. Binding both certificate fingerprints into the MAC defeats a relaying man-in-the-middle. An unpaired device that sends `offer` or `resume_request` gets `error{UNAUTHORIZED}` and is disconnected.

## Transfer

```
C → S  offer{sessionId, chunkSize, files[{fileId, name, relativeDir, size, mimeType, modifiedAt}]}
       (receiver: validation, storage preflight, user prompt; times out after 120 s)
S → C  offer_response{accepted, acceptedFileIds | reason}
loop per accepted file:
  C → S  file_start{fileId, offset}
  C → S  DATA … (sequential, chunk-aligned offsets)
  S → C  ack{fileId, offset}           after fsync + persisted checkpoint (every 8 MiB)
  C → S  file_end{fileId, size, digest}
  S → C  file_result{fileId, COMPLETED|FAILED|SKIPPED, error, savedAs}
C → S  session_end                     S → C  session_end
```

- **Bounded buffering:** the sender stops reading once more than 32 MiB is unacknowledged. The receiver's frame queue holds 4 frames.
- **Duplicates** (offset below the written offset) are dropped. A **gap**, a malformed chunk, or a chunk-hash mismatch makes the receiver roll back to its checkpoint and send `resend{fileId, offset}`. There are at most 3 chunk retries per file.
- **Keepalive:** `ping`/`pong` every 5 s. A peer silent for 30 s is treated as disconnected (`TIMEOUT`).
- **Pause/resume:** `pause{byReceiver}` / `resume{byReceiver}`.
- **Cancellation:** `cancel_file{fileId, reason}` or `cancel{reason}`. Partial data for cancelled files is deleted, and completed files are always kept. The side that cancels drains the socket until the peer closes. Closing with unread input makes TCP send RST, which can discard the cancel message before the peer reads it.

## Storage problems mid-transfer

If a write fails, the receiver classifies the error: `STORAGE_FULL`, `PERMISSION_DENIED`, `DESTINATION_UNAVAILABLE`, or `WRITE_FAILED`. It then truncates the partial file to its last checkpoint and sends `blocked{fileId, verifiedOffset, reason, requiredBytes, availableBytes}`. The connection stays open. After the user frees space or picks another folder, the receiver revalidates the partial data and the destination and sends `unblocked{fileId, offset}`. The sender continues from that offset. A per-file free-space check before `file_start` can also trigger `blocked{INSUFFICIENT_STORAGE}` without writing anything.

## Resume

After a reconnect, or after either app restarts, the sender sends `resume_request{sessionId}`. The receiver checks that the session belongs to the **same peer fingerprint** (otherwise `UNAUTHORIZED`). It re-hashes every checkpointed chunk of each partial file, truncates at the first mismatch, and replies:

```
resume_state{ok, files[{fileId, status, verifiedOffset, prefixDigest}]}
```

The sender re-reads its own source prefix, recomputes `prefixDigest`, and continues from `verifiedOffset`. If the digests differ, it sends `restart_file{fileId, RESUME_INVALID}` and starts that file from 0, and the user is told why. Once the receiver has accepted, the sender reconnects automatically up to 3 times (1 s, 3 s, 8 s), using fresh addresses from discovery if the receiver's IP changed.

## Integrity

- **Per chunk:** SHA-256 of each chunk travels in the DATA frame and is verified before writing. Verified hashes go to an append-only log next to the partial file.
- **Per file:** `digest = SHA-256(chunkHash₀ ‖ chunkHash₁ ‖ … ‖ chunkHashₙ₋₁)`, with the chunk size fixed by the offer. At `file_end` the receiver re-reads the partial file *from disk*, recomputes the digest, and compares. On mismatch the partial file is deleted, and `file_result{FAILED, INTEGRITY_FAILED}` is sent. The sender re-sends the file once, then reports failure.
- **Prefix digest** for resume: the same construction over the first *k* verified chunks.

## Receiver files

```
<destination>/.transferx-partial/<sessionId>/manifest.json   session + per-file state (atomic writes)
                                           /<fileId>.part     data (never under the final name)
                                           /<fileId>.hashes   chunk-hash log
                                           /<fileId>.ckpt     {verifiedOffset, hashCount, hash-log digest}
```

Finalization runs in this order:
1. Record `finalPath` and the expected digest in the manifest.
2. Atomic move (`ATOMIC_MOVE`) into place, applying the conflict policy (rename, skip, or explicit replace).
3. Restore the modification time.
4. Mark the file COMPLETED.

If a crash happens between steps 2 and 4, recovery re-hashes the final file against the recorded digest.

## Names and paths

Received names are NFC-normalized. Control characters and `<>:"/\|?*` are replaced, trailing dots and spaces are trimmed, and Windows device names (`CON`, `NUL`, …) are prefixed with `_`. Names are capped at 200 UTF‑8 bytes, keeping the extension. Any file whose name contains a path separator, or whose folder contains `..`, an absolute path, or a drive letter, is **refused** (`UNSAFE_NAME`). It isn't silently rewritten. Paths are resolved, checked to be inside the destination, and never written through a symbolic link.

## Discovery (UDP 47821)

JSON announcements are sent to multicast group `239.255.47.82` (TTL 1), to `255.255.255.255`, and to each interface's directed broadcast address:

```
{"app":"transferx","v":1,"kind":"announce|query|reply","id":…,"name":…,"platform":…,"port":…,"fp":…,"transports":[…]}
```

Announcements are unauthenticated hints. Packets from non-local addresses are ignored, at most 200 packets per second are processed, and at most 256 devices are kept. A device is shown as unavailable after 15 s of silence and is forgotten after 120 s.

## Connection links and QR codes

```
transferx://connect?v=1&id=<deviceId>&n=<name>&h=<ip1,ip2>&p=<port>&fp=<sha256 hex>&c=<pairing code>
```

The link pins the receiver's fingerprint *and* carries the pairing code, so scanning it needs no typing.
