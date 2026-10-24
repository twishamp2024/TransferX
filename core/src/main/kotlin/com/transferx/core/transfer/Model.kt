package com.transferx.core.transfer

import com.transferx.core.protocol.ErrorCode
import com.transferx.core.protocol.FileStatus
import com.transferx.core.storage.PreflightResult
import kotlinx.serialization.Serializable

@Serializable
enum class Direction { SEND, RECEIVE }

@Serializable
enum class TransportKind { WIFI, BLUETOOTH }

@Serializable
enum class SessionState {
    CONNECTING,
    PAIRING,
    AWAITING_APPROVAL,
    TRANSFERRING,
    PAUSED,
    BLOCKED, // receiver is out of space or lost write permission; connection kept open
    COMPLETED, // every accepted file verified and finalized
    PARTIAL, // finished, but some files failed, were skipped, or cancelled
    FAILED,
    CANCELLED,
    REJECTED,
    INTERRUPTED, // stopped unexpectedly; can be resumed
    ;

    val terminal: Boolean
        get() = this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED || this == REJECTED

    val live: Boolean get() = !terminal && this != INTERRUPTED
}

@Serializable
enum class ConflictPolicy { RENAME, REPLACE, SKIP }

@Serializable
data class PeerInfo(
    val deviceId: String,
    val name: String,
    val platform: String,
    val fingerprint: String,
    val address: String? = null,
)

@Serializable
data class FileProgress(
    val fileId: String,
    val name: String,
    val relativeDir: String = "",
    val size: Long,
    val mimeType: String? = null,
    val bytesDone: Long = 0,
    val status: FileStatus = FileStatus.PENDING,
    val error: ErrorCode? = null,
    val savedAs: String? = null,
)

@Serializable
data class StorageIssue(
    val reason: ErrorCode,
    val fileId: String?,
    val requiredBytes: Long?,
    val availableBytes: Long?,
    val destination: String,
)

@Serializable
data class SessionSnapshot(
    val id: String,
    val direction: Direction,
    val transport: TransportKind,
    val peer: PeerInfo,
    val state: SessionState,
    val files: List<FileProgress>,
    val createdAt: Long,
    val updatedAt: Long,
    val bytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val error: ErrorCode? = null,
    val errorDetail: String? = null,
    val destination: String? = null,
    val storageIssue: StorageIssue? = null,
    val pausedByPeer: Boolean = false,
    val reconnectAttempt: Int = 0,
) {
    val totalBytes: Long get() = files.filter { it.status != FileStatus.SKIPPED }.sumOf { it.size }
    val bytesDone: Long get() = files.filter { it.status != FileStatus.SKIPPED }.sumOf { it.bytesDone }
    val completedCount: Int get() = files.count { it.status == FileStatus.COMPLETED }

    /** A stopped session that still has unfinished files, so it can be resumed (or retried). */
    val resumable: Boolean
        get() = state == SessionState.INTERRUPTED ||
            (state == SessionState.PARTIAL && files.any { it.status == FileStatus.INTERRUPTED })

    val retryable: Boolean
        get() = state.terminal && files.any {
            it.status == FileStatus.FAILED || it.status == FileStatus.CANCELLED || it.status == FileStatus.INTERRUPTED
        }
}

/** Reconnection details for a sender session, persisted so a session can resume after restart. */
@Serializable
data class ConnectInfo(
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val deviceId: String,
)

/** A persisted transfer: the snapshot plus what's needed to resume or retry it. */
@Serializable
data class TransferRecord(
    val snapshot: SessionSnapshot,
    val sources: List<SourceRef> = emptyList(),
    /** File metadata exactly as offered, so a resumed session re-offers identical ids, sizes and timestamps. */
    val offered: List<com.transferx.core.protocol.FileMeta> = emptyList(),
    val connect: ConnectInfo? = null,
    val chunkSize: Int = 0,
)

/** Files offered to this device, as shown on the Incoming Transfer screen. */
@Serializable
data class OfferedFile(
    val fileId: String,
    val name: String,
    val relativeDir: String,
    val size: Long,
    val mimeType: String?,
    val conflictsWithExisting: Boolean,
    val fits: Boolean,
    val tooLargeForFilesystem: Boolean,
    /** Refused because its name or folder path was unsafe (e.g. path traversal). */
    val unsafe: Boolean = false,
)

sealed class Prompt {
    abstract val id: String

    /** Receiver: someone wants to send files. Requires Accept or Reject. */
    data class IncomingOffer(
        override val id: String,
        val sessionId: String,
        val peer: PeerInfo,
        val transport: TransportKind,
        val files: List<OfferedFile>,
        val destination: String,
        val preflight: PreflightResult,
        val expiresAt: Long,
    ) : Prompt() {
        val totalBytes: Long get() = files.sumOf { it.size }
    }

    /** Receiver: an unpaired device asked to pair; show [code] so the sender's user can type it. */
    data class PairingRequested(
        override val id: String,
        val peer: PeerInfo,
        val code: String,
        val expiresAt: Long,
    ) : Prompt()

    /** Sender: enter the code shown on the receiving device. */
    data class PairingCodeNeeded(
        override val id: String,
        val sessionId: String,
        val peer: PeerInfo,
        val attemptsRemaining: Int,
        val previousAttemptFailed: Boolean,
    ) : Prompt()
}

data class OfferDecision(
    val accept: Boolean,
    /** Null means all files that fit. */
    val acceptedFileIds: Set<String>? = null,
    val destination: java.nio.file.Path? = null,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
)
