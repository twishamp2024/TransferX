package com.transferx.ui

import com.transferx.core.protocol.ErrorCode
import com.transferx.core.protocol.FileStatus
import com.transferx.core.transfer.SessionState
import java.text.DateFormat
import java.util.Date

object Format {
    /** Decimal units (1 MB = 1,000,000 bytes), as file managers on macOS and Android show them. */
    fun bytes(n: Long?): String {
        if (n == null) return "unknown"
        if (n < 1000) return "$n B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = n / 1000.0
        var u = 0
        while (v >= 1000 && u < units.lastIndex) {
            v /= 1000; u++
        }
        return if (v >= 100) "%.0f %s".format(v, units[u]) else "%.1f %s".format(v, units[u])
    }

    fun speed(bps: Long?): String? = bps?.let { "${bytes(it)}/s" }

    fun duration(seconds: Long?): String? {
        if (seconds == null) return null
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    fun time(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun platform(p: String): String = when (p.lowercase()) {
        "android" -> "Android"
        "ios" -> "iOS"
        "windows" -> "Windows"
        "macos" -> "macOS"
        "linux" -> "Linux"
        else -> p.replaceFirstChar { it.uppercase() }
    }

    fun state(s: SessionState): String = when (s) {
        SessionState.CONNECTING -> "Connecting"
        SessionState.PAIRING -> "Pairing"
        SessionState.AWAITING_APPROVAL -> "Waiting for approval"
        SessionState.TRANSFERRING -> "Transferring"
        SessionState.PAUSED -> "Paused"
        SessionState.BLOCKED -> "Blocked"
        SessionState.COMPLETED -> "Completed"
        SessionState.PARTIAL -> "Partly completed"
        SessionState.FAILED -> "Failed"
        SessionState.CANCELLED -> "Cancelled"
        SessionState.REJECTED -> "Declined"
        SessionState.INTERRUPTED -> "Interrupted"
    }

    fun fileStatus(s: FileStatus): String = when (s) {
        FileStatus.PENDING -> "Waiting"
        FileStatus.TRANSFERRING -> "Transferring"
        FileStatus.PAUSED -> "Paused"
        FileStatus.BLOCKED -> "Blocked"
        FileStatus.VERIFYING -> "Verifying"
        FileStatus.COMPLETED -> "Done"
        FileStatus.SKIPPED -> "Skipped"
        FileStatus.FAILED -> "Failed"
        FileStatus.CANCELLED -> "Cancelled"
        FileStatus.INTERRUPTED -> "Interrupted"
    }

    /** What the user can do about an error, in plain words. */
    fun advice(e: ErrorCode): String? = when (e.category) {
        ErrorCode.Category.NETWORK -> "Check that both devices are on the same network, then resume."
        ErrorCode.Category.STORAGE -> "Free up space or choose another folder on the receiving device, then try again."
        ErrorCode.Category.PERMISSION -> "Grant access to the file or folder again, then retry."
        ErrorCode.Category.INTEGRITY -> "Retry the transfer. If it keeps failing, the storage device may be faulty."
        ErrorCode.Category.SECURITY -> "Pair the devices again using the code shown on the receiver."
        else -> null
    }
}
