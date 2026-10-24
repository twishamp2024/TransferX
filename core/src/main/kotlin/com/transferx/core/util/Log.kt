package com.transferx.core.util

import java.time.Instant
import java.util.ArrayDeque

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(val time: Long, val level: LogLevel, val tag: String, val message: String)

fun interface LogSink {
    fun log(level: LogLevel, tag: String, message: String, error: Throwable?)
}

/**
 * Minimal logging facade.
 *
 * Diagnostics policy: callers must never pass file contents, pairing codes, or full
 * key material. Fingerprints are logged only in shortened form ([shortFp]).
 */
object Log {
    @Volatile var minLevel: LogLevel = LogLevel.INFO
    @Volatile var sink: LogSink = LogSink { level, tag, message, error ->
        val line = "${Instant.now()} ${level.name.padEnd(5)} [$tag] $message"
        if (level >= LogLevel.WARN) System.err.println(line) else println(line)
        if (error != null && minLevel == LogLevel.DEBUG) error.printStackTrace()
    }

    private const val RING_SIZE = 500
    private val ring = ArrayDeque<LogEntry>(RING_SIZE)

    /** Recent log lines for the in-app diagnostics view. */
    fun recent(): List<LogEntry> = synchronized(ring) { ring.toList() }

    fun d(tag: String, message: String) = emit(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = emit(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = emit(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = emit(LogLevel.ERROR, tag, message, error)

    private fun emit(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (level < minLevel) return
        val text = if (error != null) "$message (${error.javaClass.simpleName}: ${error.message})" else message
        synchronized(ring) {
            if (ring.size == RING_SIZE) ring.removeFirst()
            ring.addLast(LogEntry(System.currentTimeMillis(), level, tag, text))
        }
        sink.log(level, tag, text, error)
    }

    fun shortFp(fingerprintHex: String): String = fingerprintHex.take(8)

    /** Also writes log lines to [dir]/transferx.log (rotated at 1 MB, one backup kept). */
    fun addFileSink(dir: java.nio.file.Path) {
        val file = dir.resolve("transferx.log")
        val previous = sink
        sink = LogSink { level, tag, message, error ->
            previous.log(level, tag, message, error)
            try {
                synchronized(this) {
                    java.nio.file.Files.createDirectories(dir)
                    if (java.nio.file.Files.exists(file) && java.nio.file.Files.size(file) > 1_000_000) {
                        java.nio.file.Files.move(file, dir.resolve("transferx.log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    java.nio.file.Files.write(
                        file, "${Instant.now()} ${level.name.padEnd(5)} [$tag] $message\n".toByteArray(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND,
                    )
                }
            } catch (_: Exception) {
                // Diagnostics must never break the app.
            }
        }
    }
}
