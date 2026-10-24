package com.transferx.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1

/** Per-file status as seen by both peers. Terminal states never change. */
@Serializable
enum class FileStatus {
    PENDING,
    TRANSFERRING,
    PAUSED,
    BLOCKED, // waiting for storage space or permission at the receiver
    VERIFYING,
    COMPLETED,
    SKIPPED,
    FAILED,
    CANCELLED,
    INTERRUPTED, // resumable
    ;

    val terminal: Boolean get() = this == COMPLETED || this == SKIPPED || this == FAILED || this == CANCELLED
}

@Serializable
data class FileMeta(
    val fileId: String,
    val name: String,
    /** Folder path relative to the transfer root, '/'-separated, empty for top-level files. */
    val relativeDir: String = "",
    val size: Long,
    val mimeType: String? = null,
    val modifiedAt: Long? = null,
)

@Serializable
data class FileResumeInfo(
    val fileId: String,
    val status: FileStatus,
    /** Bytes the receiver holds durably and has verified chunk-by-chunk. */
    val verifiedOffset: Long,
    /** SHA-256 over the concatenated chunk hashes of [0, verifiedOffset), hex. */
    val prefixDigest: String? = null,
    val error: ErrorCode? = null,
)

/** Control messages. They're sent as JSON in CONTROL frames; file data uses binary DATA frames. */
@Serializable
sealed class Message

@Serializable @SerialName("hello")
data class Hello(
    val protocolVersion: Int,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val transports: List<String> = emptyList(),
) : Message()

/** Server → client after hello: whether the server already trusts the client's certificate. */
@Serializable @SerialName("auth_status")
data class AuthStatus(val peerTrusted: Boolean) : Message()

@Serializable @SerialName("pair_request")
data class PairRequest(val reason: String = "") : Message()

@Serializable @SerialName("pair_challenge")
data class PairChallenge(val expiresInMs: Long, val attemptsRemaining: Int) : Message()

@Serializable @SerialName("pair_proof")
data class PairProof(val mac: String) : Message()

@Serializable @SerialName("pair_result")
data class PairResult(val ok: Boolean, val mac: String? = null, val attemptsRemaining: Int = 0) : Message()

@Serializable @SerialName("offer")
data class Offer(val sessionId: String, val chunkSize: Int, val files: List<FileMeta>) : Message()

@Serializable @SerialName("offer_response")
data class OfferResponse(
    val sessionId: String,
    val accepted: Boolean,
    val acceptedFileIds: List<String> = emptyList(),
    val reason: ErrorCode? = null,
) : Message()

@Serializable @SerialName("file_start")
data class FileStart(val fileId: String, val offset: Long) : Message()

@Serializable @SerialName("ack")
data class Ack(val fileId: String, val offset: Long) : Message()

/** Receiver → sender: re-send this file from [offset] (a chunk failed verification). */
@Serializable @SerialName("resend")
data class Resend(val fileId: String, val offset: Long) : Message()

@Serializable @SerialName("file_end")
/** [digest]: SHA-256 chunk-list digest of the whole file (see FileDigest). */
data class FileEnd(val fileId: String, val size: Long, val digest: String) : Message()

@Serializable @SerialName("file_result")
data class FileResult(
    val fileId: String,
    val status: FileStatus,
    val error: ErrorCode? = null,
    /** Final path relative to the destination, for display only. */
    val savedAs: String? = null,
) : Message()

/** Receiver → sender: writes stopped; data up to [verifiedOffset] is safe. */
@Serializable @SerialName("blocked")
data class Blocked(
    val fileId: String,
    val verifiedOffset: Long,
    val reason: ErrorCode,
    val requiredBytes: Long? = null,
    val availableBytes: Long? = null,
) : Message()

/** Receiver → sender: the blocking condition was resolved; continue [fileId] from [offset]. */
@Serializable @SerialName("unblocked")
data class Unblocked(val fileId: String, val offset: Long) : Message()

@Serializable @SerialName("pause")
data class Pause(val byReceiver: Boolean) : Message()

@Serializable @SerialName("resume")
data class ResumeTransfer(val byReceiver: Boolean) : Message()

@Serializable @SerialName("cancel_file")
data class CancelFile(val fileId: String, val reason: ErrorCode) : Message()

@Serializable @SerialName("cancel")
data class CancelSession(val reason: ErrorCode) : Message()

@Serializable @SerialName("resume_request")
data class ResumeRequest(val sessionId: String) : Message()

@Serializable @SerialName("resume_state")
data class ResumeState(
    val sessionId: String,
    val ok: Boolean,
    val files: List<FileResumeInfo> = emptyList(),
    val error: ErrorCode? = null,
) : Message()

/** Sender → receiver: its source no longer matches the receiver's partial data; discard and restart. */
@Serializable @SerialName("restart_file")
data class RestartFile(val fileId: String, val reason: ErrorCode) : Message()

@Serializable @SerialName("session_end")
data class SessionEnd(val sessionId: String) : Message()

@Serializable @SerialName("ping")
data class Ping(val t: Long) : Message()

@Serializable @SerialName("pong")
data class Pong(val t: Long) : Message()

@Serializable @SerialName("error")
data class ProtocolErrorMessage(val code: ErrorCode, val detail: String = "") : Message()

val ProtocolJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true // forward compatibility: newer peers may add fields
    encodeDefaults = true
    explicitNulls = false
}
