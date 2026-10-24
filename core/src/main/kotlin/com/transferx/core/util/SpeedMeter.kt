package com.transferx.core.util

import java.util.ArrayDeque

/**
 * Measures throughput from real byte counts over a sliding window.
 * Reports null until there is enough data for a meaningful figure, so the UI never
 * shows an invented speed or ETA.
 */
class SpeedMeter(
    private val windowNanos: Long = 3_000_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Sample(val t: Long, val total: Long)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun record(totalBytes: Long) {
        val now = nanoTime()
        samples.addLast(Sample(now, totalBytes))
        while (samples.size > 2 && now - samples.peekFirst().t > windowNanos) samples.removeFirst()
    }

    @Synchronized
    fun reset() = samples.clear()

    /** Bytes per second, or null if fewer than ~0.5 s of samples exist. */
    @Synchronized
    fun bytesPerSecond(): Long? {
        if (samples.size < 2) return null
        val first = samples.peekFirst()
        val last = samples.peekLast()
        val dt = last.t - first.t
        if (dt < 500_000_000L) return null
        val db = last.total - first.total
        if (db < 0) return null
        return db * 1_000_000_000L / dt
    }

    fun etaSeconds(remainingBytes: Long): Long? {
        val bps = bytesPerSecond() ?: return null
        if (bps <= 0) return null
        return (remainingBytes + bps - 1) / bps
    }
}
