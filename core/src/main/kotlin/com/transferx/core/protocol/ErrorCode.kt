package com.transferx.core.protocol

import kotlinx.serialization.Serializable

/**
 * Error codes shared by the protocol, the engine, and the UI. Each code has a category,
 * so the UI can tell a user cancellation from a network, storage, permission, or
 * integrity problem.
 */
@Serializable
enum class ErrorCode(val category: Category, val userMessage: String) {
    USER_CANCELLED(Category.CANCELLED, "You cancelled the transfer."),
    PEER_CANCELLED(Category.CANCELLED, "The other device cancelled the transfer."),
    REJECTED(Category.REJECTED, "The receiver declined the transfer."),
    APPROVAL_TIMEOUT(Category.REJECTED, "The receiver did not respond in time."),
    NOT_ACCEPTING(Category.REJECTED, "The receiver is not accepting transfers right now."),

    NETWORK(Category.NETWORK, "The connection was lost."),
    TIMEOUT(Category.NETWORK, "The other device stopped responding."),
    CONNECT_FAILED(Category.NETWORK, "Could not connect to the other device."),
    DIFFERENT_NETWORK(
        Category.NETWORK,
        "The other device appears to be on a different network. Join the same Wi-Fi network, " +
            "use a phone hotspot, or (Android only) use Bluetooth.",
    ),

    INSUFFICIENT_STORAGE(Category.STORAGE, "There is not enough free space at the destination."),
    STORAGE_FULL(Category.STORAGE, "The destination ran out of space during the transfer."),
    FILE_TOO_LARGE_FOR_FILESYSTEM(Category.STORAGE, "The destination filesystem cannot store a file this large."),
    DESTINATION_UNAVAILABLE(Category.STORAGE, "The destination folder is unavailable."),
    WRITE_FAILED(Category.STORAGE, "Writing to the destination failed."),

    PERMISSION_DENIED(Category.PERMISSION, "Permission to access the file or folder was denied."),
    SOURCE_UNREADABLE(Category.PERMISSION, "The file can no longer be read on the sending device."),

    INTEGRITY_FAILED(Category.INTEGRITY, "The received data did not match the original (integrity check failed)."),
    SOURCE_CHANGED(Category.INTEGRITY, "The file was modified on the sending device during the transfer."),
    RESUME_INVALID(Category.INTEGRITY, "The partial file could not be verified, so it restarted from the beginning."),

    UNAUTHORIZED(Category.SECURITY, "The device is not authorized for this transfer."),
    UNSAFE_NAME(Category.SECURITY, "The file name or folder path was unsafe and the file was refused."),
    PAIRING_FAILED(Category.SECURITY, "Pairing failed. Check the code and try again."),
    IDENTITY_MISMATCH(
        Category.SECURITY,
        "This device's identity does not match the one you paired with. It may be a different device.",
    ),

    CONFLICT_SKIPPED(Category.SKIPPED, "Skipped because a file with the same name already exists."),
    NOT_SELECTED(Category.SKIPPED, "The receiver did not select this file."),

    SESSION_NOT_FOUND(Category.PROTOCOL, "The other device has no record of this transfer."),
    EXPIRED(Category.PROTOCOL, "The incomplete transfer expired and its temporary data was removed."),
    UNSUPPORTED_VERSION(Category.PROTOCOL, "The other device runs an incompatible TransferX version."),
    PROTOCOL_ERROR(Category.PROTOCOL, "The other device sent an invalid message."),
    BUSY(Category.PROTOCOL, "The other device is busy with too many transfers."),
    RATE_LIMITED(Category.PROTOCOL, "Too many attempts. Wait a moment and try again."),
    INTERNAL(Category.PROTOCOL, "An unexpected error occurred."),
    ;

    enum class Category { CANCELLED, REJECTED, NETWORK, STORAGE, PERMISSION, INTEGRITY, SECURITY, SKIPPED, PROTOCOL }

    /** Errors after which the same session can continue via resume. */
    val resumable: Boolean
        get() = category == Category.NETWORK || category == Category.STORAGE || category == Category.PERMISSION ||
            this == TIMEOUT
}

/** Thrown inside the engine to carry a protocol-level error code. */
class TransferException(val code: ErrorCode, message: String = code.userMessage, cause: Throwable? = null) :
    Exception(message, cause)
