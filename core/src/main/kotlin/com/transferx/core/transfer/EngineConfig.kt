package com.transferx.core.transfer

data class EngineConfig(
    /** Bytes per DATA frame and per chunk hash. Fixed per session; announced in the offer. */
    val chunkSize: Int = 512 * 1024,
    /** Receiver fsyncs and checkpoints after this many bytes; also the ACK granularity. */
    val checkpointIntervalBytes: Long = 8L * 1024 * 1024,
    /** Sender stops reading when this many bytes are unacknowledged (bounded buffering). */
    val maxInFlightBytes: Long = 32L * 1024 * 1024,
    val connectTimeoutMs: Int = 8_000,
    val handshakeTimeoutMs: Int = 15_000,
    val idleTimeoutMs: Long = 30_000,
    val pingIntervalMs: Long = 5_000,
    val approvalTimeoutMs: Long = 120_000,
    val pairingCodeTtlMs: Long = 5 * 60_000,
    val maxPairingAttempts: Int = 5,
    val maxConcurrentIncoming: Int = 4,
    val maxConnections: Int = 16,
    val maxConnectionsPerMinutePerHost: Int = 30,
    val storageReserveBytes: Long = 16L * 1024 * 1024,
    val maxFilesPerOffer: Int = 10_000,
    /** Times a file is re-sent from scratch after a whole-file integrity failure. */
    val integrityRetries: Int = 1,
    /** Times a chunk may fail its hash check before the file fails. */
    val chunkRetries: Int = 3,
    /** Automatic reconnect attempts after a network drop (once the transfer was accepted). */
    val maxReconnectAttempts: Int = 3,
    val reconnectBackoffMs: List<Long> = listOf(1_000, 3_000, 8_000),
    /** Incomplete receive data older than this is deleted on startup. */
    val partialRetentionMs: Long = 7L * 24 * 3600 * 1000,
    /** Off by default: connections from public (non-RFC1918/ULA/link-local) addresses are refused. */
    val allowPublicAddresses: Boolean = false,
) {
    init {
        require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "chunkSize out of range" }
        require(checkpointIntervalBytes >= chunkSize)
        require(maxInFlightBytes > checkpointIntervalBytes) { "window must exceed checkpoint interval" }
    }

    companion object {
        const val MIN_CHUNK = 16 * 1024
        const val MAX_CHUNK = 4 * 1024 * 1024
        const val DEFAULT_PORT = 47820
    }
}
