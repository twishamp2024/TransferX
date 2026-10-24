package com.transferx.core.security

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession

/**
 * Runs TLS over an arbitrary byte stream (for example a Bluetooth RFCOMM socket, which
 * can't be wrapped by SSLSocket) using [SSLEngine].
 *
 * Reads and writes may happen concurrently on different threads. SSLEngine allows
 * concurrent wrap/unwrap. Post-handshake messages that need a wrap from the read path
 * take the write lock.
 */
class TlsStream(
    private val engine: SSLEngine,
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
    private val closeRaw: () -> Unit,
) {
    private val readLock = Any()
    private val writeLock = Any()

    // All three buffers are kept in "write mode" between operations.
    private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize)
    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)

    @Volatile private var inboundDone = false

    val session: SSLSession get() = engine.session

    fun handshake() {
        engine.beginHandshake()
        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_WRAP -> synchronized(writeLock) { wrapAndSend(EMPTY) }
                HandshakeStatus.NEED_UNWRAP, HandshakeStatus.NEED_UNWRAP_AGAIN -> synchronized(readLock) {
                    val r = unwrapOnce()
                    if (r == null) throw EOFException("Connection closed during TLS handshake")
                    if (r.status == SSLEngineResult.Status.CLOSED) throw SSLException("TLS closed during handshake")
                }
                HandshakeStatus.NEED_TASK -> runTasks()
                HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING, null -> return
            }
        }
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            synchronized(readLock) {
                while (true) {
                    if (appIn.position() > 0) {
                        appIn.flip()
                        val n = minOf(len, appIn.remaining())
                        appIn.get(b, off, n)
                        appIn.compact()
                        return n
                    }
                    if (inboundDone) return -1
                    val r = unwrapOnce() ?: run { inboundDone = true; return -1 }
                    if (r.status == SSLEngineResult.Status.CLOSED) inboundDone = true
                    handlePostHandshake(r.handshakeStatus)
                }
            }
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(writeLock) {
                val src = ByteBuffer.wrap(b, off, len)
                while (src.hasRemaining()) wrapAndSend(src)
            }
        }

        override fun flush() = synchronized(writeLock) { rawOut.flush() }
    }

    fun close() {
        try {
            synchronized(writeLock) {
                engine.closeOutbound()
                while (!engine.isOutboundDone) wrapAndSend(EMPTY)
            }
        } catch (_: Exception) {
        } finally {
            closeRaw()
        }
    }

    /** Must hold readLock. Returns null on EOF of the raw stream. */
    private fun unwrapOnce(): SSLEngineResult? {
        while (true) {
            netIn.flip()
            val r = try {
                engine.unwrap(netIn, appIn)
            } finally {
                netIn.compact()
            }
            when (r.status) {
                SSLEngineResult.Status.OK, SSLEngineResult.Status.CLOSED -> {
                    if (r.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
                    return r
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    appIn = grow(appIn, engine.session.applicationBufferSize)
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    if (netIn.remaining() < engine.session.packetBufferSize) {
                        netIn = grow(netIn, engine.session.packetBufferSize)
                    }
                    val n = rawIn.read(netIn.array(), netIn.arrayOffset() + netIn.position(), netIn.remaining())
                    if (n < 0) {
                        try {
                            engine.closeInbound()
                        } catch (_: SSLException) {
                            // Truncation without close_notify: treated as a connection drop.
                        }
                        return null
                    }
                    netIn.position(netIn.position() + n)
                }
                null -> throw SSLException("Unexpected TLS status")
            }
        }
    }

    /** Must hold writeLock. */
    private fun wrapAndSend(src: ByteBuffer) {
        while (true) {
            netOut.clear()
            val r = engine.wrap(src, netOut)
            when (r.status) {
                SSLEngineResult.Status.OK, SSLEngineResult.Status.CLOSED -> {
                    netOut.flip()
                    if (netOut.hasRemaining()) {
                        rawOut.write(netOut.array(), netOut.arrayOffset(), netOut.remaining())
                        rawOut.flush()
                    }
                    if (r.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
                    if (r.status == SSLEngineResult.Status.CLOSED && src.hasRemaining()) {
                        throw IOException("TLS connection closed")
                    }
                    return
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                else -> throw SSLException("Unexpected TLS wrap status ${r.status}")
            }
        }
    }

    private fun handlePostHandshake(status: HandshakeStatus) {
        when (status) {
            HandshakeStatus.NEED_TASK -> runTasks()
            HandshakeStatus.NEED_WRAP -> synchronized(writeLock) {
                while (engine.handshakeStatus == HandshakeStatus.NEED_WRAP) wrapAndSend(EMPTY)
            }
            else -> {}
        }
    }

    private fun runTasks() {
        while (true) {
            val task = engine.delegatedTask ?: return
            task.run()
        }
    }

    private fun grow(buf: ByteBuffer, min: Int): ByteBuffer {
        val bigger = ByteBuffer.allocate(maxOf(buf.capacity() * 2, buf.position() + min))
        buf.flip()
        bigger.put(buf)
        return bigger
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
