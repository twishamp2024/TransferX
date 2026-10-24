package com.transferx.core.transfer

import com.transferx.core.protocol.AuthStatus
import com.transferx.core.protocol.ErrorCode
import com.transferx.core.protocol.Hello
import com.transferx.core.protocol.Message
import com.transferx.core.protocol.PROTOCOL_VERSION
import com.transferx.core.protocol.PairChallenge
import com.transferx.core.protocol.PairProof
import com.transferx.core.protocol.PairRequest
import com.transferx.core.protocol.PairResult
import com.transferx.core.protocol.ProtocolErrorMessage
import com.transferx.core.protocol.TransferException
import com.transferx.core.security.PairingCodes
import com.transferx.core.security.TrustedDevice
import com.transferx.core.util.Hashing
import com.transferx.core.util.Ids
import com.transferx.core.util.Log

private const val TAG = "Handshake"

internal fun TransferEngine.localHello(): Hello {
    val l = localDevice()
    return Hello(PROTOCOL_VERSION, identity.deviceId, l.name, l.platform, l.appVersion, l.transports)
}

/** Validates and normalizes a peer's hello. Device names are display-only and untrusted. */
internal fun validateHello(m: Message, fingerprint: String, address: String?): PeerInfo {
    val hello = m as? Hello ?: throw TransferException(ErrorCode.PROTOCOL_ERROR, "expected hello")
    if (hello.protocolVersion != PROTOCOL_VERSION) throw TransferException(ErrorCode.UNSUPPORTED_VERSION)
    if (!Ids.isValid(hello.deviceId)) throw TransferException(ErrorCode.PROTOCOL_ERROR, "invalid device id")
    val name = hello.deviceName.filter { it.code >= 0x20 && it.code != 0x7f }.trim().take(64).ifEmpty { "Unknown device" }
    val platform = hello.platform.filter { it.isLetterOrDigit() }.take(16).ifEmpty { "unknown" }
    return PeerInfo(hello.deviceId, name, platform, fingerprint, address)
}

/**
 * Client side: exchange hellos, check the server's certificate against the pinned or
 * trusted fingerprint, and pair with a code if either side doesn't trust the other.
 * Returns only once both sides have authenticated each other.
 */
internal suspend fun TransferEngine.clientHandshake(
    io: SessionIo,
    channel: SecureChannel,
    target: ConnectTarget,
    sessionId: String,
    onPairing: () -> Unit,
): PeerInfo {
    val t = config.handshakeTimeoutMs.toLong()
    io.conn.send(localHello())
    val peer = validateHello(io.awaitControl(t), channel.peerFingerprint, channel.remoteAddress)
    val serverFp = channel.peerFingerprint

    // Identity check before any file metadata is sent.
    val pinned = target.expectedFingerprint ?: trustStore.byDeviceId(peer.deviceId)?.fingerprint
    if (pinned != null && pinned != serverFp) {
        Log.w(TAG, "Identity mismatch for ${peer.name}: expected ${Log.shortFp(pinned)}, got ${Log.shortFp(serverFp)}")
        throw TransferException(ErrorCode.IDENTITY_MISMATCH)
    }
    val iTrustServer = trustStore.isTrusted(serverFp)
    val status = io.awaitControl(t) as? AuthStatus ?: throw TransferException(ErrorCode.PROTOCOL_ERROR, "expected auth_status")
    if (status.peerTrusted && iTrustServer) {
        trustStore.touch(serverFp, peer.name)
        return peer
    }

    onPairing()
    io.conn.send(PairRequest())
    val challenge = io.awaitControl(t) as? PairChallenge
        ?: throw TransferException(ErrorCode.PAIRING_FAILED, "receiver refused pairing")
    var attemptsLeft = challenge.attemptsRemaining
    var presetCode = target.pairingCode?.let { PairingCodes.normalize(it) }
    var failedBefore = false
    while (attemptsLeft > 0) {
        val code = presetCode ?: requestPairingCode(sessionId, peer, attemptsLeft, failedBefore)
            ?: throw TransferException(ErrorCode.USER_CANCELLED)
        presetCode = null
        val myFp = identity.fingerprintHex
        io.conn.send(PairProof(PairingCodes.proof(code, PairingCodes.Role.CLIENT, myFp, serverFp)))
        // The user may take a while to type the code on the other side, but the proof is
        // already sent, so a short timeout is enough here.
        val result = io.awaitControl(t) as? PairResult ?: throw TransferException(ErrorCode.PROTOCOL_ERROR)
        if (result.ok) {
            val expected = PairingCodes.proof(code, PairingCodes.Role.SERVER, myFp, serverFp)
            val mac = result.mac ?: ""
            if (!Hashing.constantTimeEquals(expected.toByteArray(), mac.toByteArray())) {
                // The receiver accepted without proving it knows the code: possible MITM.
                throw TransferException(ErrorCode.PAIRING_FAILED, "receiver failed to prove the pairing code")
            }
            val now = System.currentTimeMillis()
            trustStore.trust(TrustedDevice(peer.deviceId, peer.name, peer.platform, serverFp, now, now))
            Log.i(TAG, "Paired with ${peer.name} (${Log.shortFp(serverFp)})")
            return peer
        }
        attemptsLeft = result.attemptsRemaining
        failedBefore = true
    }
    throw TransferException(ErrorCode.PAIRING_FAILED)
}

/**
 * Server side up to authentication. Returns the authenticated peer and the first
 * post-authentication message (offer or resume request).
 */
internal suspend fun TransferEngine.serverHandshake(io: SessionIo, channel: SecureChannel): Pair<PeerInfo, Message> {
    val t = config.handshakeTimeoutMs.toLong()
    io.conn.send(localHello())
    val peer = validateHello(io.awaitControl(t), channel.peerFingerprint, channel.remoteAddress)
    var trusted = trustStore.isTrusted(channel.peerFingerprint)
    io.conn.send(AuthStatus(trusted))
    while (true) {
        val m = io.awaitControl(if (trusted) t else config.pairingCodeTtlMs)
        when (m) {
            is PairRequest -> {
                if (!serverPairing(io, peer)) throw TransferException(ErrorCode.PAIRING_FAILED)
                trusted = true
            }
            else -> {
                if (!trusted) {
                    io.conn.send(ProtocolErrorMessage(ErrorCode.UNAUTHORIZED, "pair first"))
                    throw TransferException(ErrorCode.UNAUTHORIZED, "unpaired device ${peer.name} skipped pairing")
                }
                trustStore.touch(channel.peerFingerprint, peer.name)
                return peer to m
            }
        }
    }
}

private suspend fun TransferEngine.serverPairing(io: SessionIo, peer: PeerInfo): Boolean {
    val code = pairing.activeCode()
    val promptId = Ids.newId()
    addPrompt(Prompt.PairingRequested(promptId, peer, code.display, code.expiresAt))
    pairingIos[promptId] = io
    try {
        io.conn.send(PairChallenge((code.expiresAt - System.currentTimeMillis()).coerceAtLeast(0), pairing.attemptsRemaining()))
        val serverFp = identity.fingerprintHex
        while (true) {
            val remaining = (code.expiresAt - System.currentTimeMillis()).coerceAtLeast(1)
            val proof = io.awaitControl(remaining) as? PairProof ?: throw TransferException(ErrorCode.PROTOCOL_ERROR)
            if (isPairingDenied(promptId)) throw TransferException(ErrorCode.PAIRING_FAILED, "denied by user")
            val result = pairing.verify { candidate ->
                val expected = PairingCodes.proof(candidate, PairingCodes.Role.CLIENT, peer.fingerprint, serverFp)
                Hashing.constantTimeEquals(expected.toByteArray(), proof.mac.toByteArray())
            }
            val matched = result.code
            if (matched != null) {
                io.conn.send(PairResult(true, PairingCodes.proof(matched, PairingCodes.Role.SERVER, peer.fingerprint, serverFp)))
                val now = System.currentTimeMillis()
                trustStore.trust(TrustedDevice(peer.deviceId, peer.name, peer.platform, peer.fingerprint, now, now))
                Log.i(TAG, "Paired with ${peer.name} (${Log.shortFp(peer.fingerprint)})")
                return true
            }
            io.conn.send(PairResult(false, attemptsRemaining = result.attemptsRemaining))
            Log.w(TAG, "Wrong pairing code from ${peer.name}; ${result.attemptsRemaining} attempts left")
            if (result.attemptsRemaining <= 0) return false
        }
    } finally {
        pairingIos.remove(promptId)
        removePrompt(promptId)
    }
}
