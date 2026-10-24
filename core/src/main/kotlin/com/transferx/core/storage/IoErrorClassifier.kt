package com.transferx.core.storage

import com.transferx.core.protocol.ErrorCode
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.ReadOnlyFileSystemException

/**
 * Maps platform I/O failures to user-meaningful categories. Messages differ by OS:
 * Linux/Android/macOS report errno text ("No space left on device", ENOSPC, EDQUOT).
 * Windows reports "There is not enough space on the disk" (ERROR_DISK_FULL /
 * ERROR_HANDLE_DISK_FULL).
 */
object IoErrorClassifier {
    private val storageFull = listOf(
        "no space left", "enospc", "not enough space", "disk full", "disk is full", "edquot",
        "quota exceeded", "file too large", "efbig", "insufficient storage",
    )
    private val permission = listOf(
        "permission denied", "eacces", "eperm", "access is denied", "read-only file system", "erofs",
        "operation not permitted", "write protected",
    )

    fun classifyWrite(e: Throwable, usableSpaceAfter: Long? = null, chunkSize: Int = 0): ErrorCode {
        if (e is SecurityException || e is AccessDeniedException || e is ReadOnlyFileSystemException) {
            return ErrorCode.PERMISSION_DENIED
        }
        val text = describe(e)
        if (storageFull.any { it in text }) return ErrorCode.STORAGE_FULL
        if (permission.any { it in text }) return ErrorCode.PERMISSION_DENIED
        if (e is NoSuchFileException || e is FileNotFoundException) return ErrorCode.DESTINATION_UNAVAILABLE
        // Unrecognised message: if the volume is effectively full, that's the likely cause.
        if (usableSpaceAfter != null && usableSpaceAfter < maxOf(chunkSize.toLong(), 64 * 1024)) {
            return ErrorCode.STORAGE_FULL
        }
        return ErrorCode.WRITE_FAILED
    }

    fun classifyRead(e: Throwable): ErrorCode {
        if (e is SecurityException || e is AccessDeniedException) return ErrorCode.SOURCE_UNREADABLE
        if (e is NoSuchFileException || e is FileNotFoundException) return ErrorCode.SOURCE_UNREADABLE
        val text = describe(e)
        if (permission.any { it in text }) return ErrorCode.SOURCE_UNREADABLE
        return ErrorCode.SOURCE_UNREADABLE
    }

    private fun describe(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 5) {
            sb.append(t.message.orEmpty()).append(' ')
            if (t is FileSystemException) sb.append(t.reason.orEmpty()).append(' ')
            t = t.cause
            depth++
        }
        return sb.toString().lowercase()
    }

    fun isIo(e: Throwable) = e is IOException || e is SecurityException
}
