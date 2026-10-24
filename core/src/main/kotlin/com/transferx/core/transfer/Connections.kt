package com.transferx.core.transfer

import com.transferx.core.protocol.ErrorCode
import com.transferx.core.protocol.Frame
import com.transferx.core.protocol.FramedConnection
import com.transferx.core.protocol.Message
import com.transferx.core.protocol.Ping
import com.transferx.core.protocol.Pong
import com.transferx.core.protocol.TransferException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** A raw, unencrypted duplex byte stream (for example a Bluetooth RFCOMM socket). */
class RawStream(
    val input: InputStream,
    val output: OutputStream,
    val label: String,
    val close: () -> Unit,
)

sealed class ConnectTarget {
    abstract val expectedFingerprint: String?
    abstract val pairingCode: String?
    abstract val deviceId: String?

    /** Direct TCP to one of [hosts] (tried in order). */
    data class Tcp(
        val hosts: List<String>,
        val port: Int,
        override val expectedFingerprint: String? = null,
        override val pairingCode: String? = null,
        override val deviceId: String? = null,
    ) : ConnectTarget()

    /** Any stream transport. TLS runs over it via SSLEngine. */
    class Stream(
        val label: String,
        val transport: TransportKind,
        val open: suspend () -> RawStream,
        override val expectedFingerprint: String? = null,
        override val pairingCode: String? = null,
        override val deviceId: String? = null,
    ) : ConnectTarget()
}

/** An encrypted, framed connection whose peer certificate fingerprint is known (not yet trusted). */
class SecureChannel(
    val conn: FramedConnection,
    val peerFingerprint: String,
    val transport: TransportKind,
    val remoteAddress: String?,
)

/**
 * Reads frames on a dedicated coroutine and hands them to the session through a
 * bounded channel (backpressure on the socket), answers pings, sends keepalives, and
 * closes the connection when the peer has been silent longer than the idle timeout.
 */
internal class SessionIo(
    val conn: FramedConnection,
    private val scope: CoroutineScope,
    private val pingIntervalMs: Long,
    private val idleTimeoutMs: Long,
    capacity: Int = 4,
) {
    val frames = Channel<Frame>(capacity)
    @Volatile private var lastReceivedAt = System.nanoTime()
    @Volatile var closeCause: Throwable? = null
        private set
    private val jobs = mutableListOf<Job>()

    fun start(): SessionIo {
        jobs += scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val f = conn.readFrame()
                    lastReceivedAt = System.nanoTime()
                    if (f is Frame.Control) {
                        val m = f.message
                        if (m is Ping) {
                            conn.send(Pong(m.t)); continue
                        }
                        if (m is Pong) continue
                    }
                    frames.send(f)
                }
            } catch (e: Throwable) {
                if (closeCause == null) closeCause = e
                frames.close(e)
            }
        }
        jobs += scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(pingIntervalMs)
                if ((System.nanoTime() - lastReceivedAt) / 1_000_000 > idleTimeoutMs) {
                    closeCause = TransferException(ErrorCode.TIMEOUT)
                    conn.close()
                    break
                }
                try {
                    conn.send(Ping(System.currentTimeMillis()))
                } catch (e: Exception) {
                    break
                }
            }
        }
        return this
    }

    /** Waits for the next control message, answering nothing else. Used during handshakes. */
    suspend fun awaitControl(timeoutMs: Long): Message {
        val result = withTimeoutOrNull(timeoutMs) { frames.receiveCatching() }
            ?: throw TransferException(ErrorCode.TIMEOUT)
        val frame = result.getOrNull() ?: throw networkError(result.exceptionOrNull())
        return (frame as? Frame.Control)?.message ?: throw TransferException(ErrorCode.PROTOCOL_ERROR, "unexpected data frame")
    }

    /**
     * Graceful shutdown after sending a final message: keep reading (and discarding) until
     * the peer closes or [timeoutMs] passes. Closing a TCP socket that still has unread
     * input makes the OS send RST, which can destroy our last message before the peer has
     * read it.
     */
    suspend fun drainUntilClosed(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (frames.receiveCatching().isClosed) break
            }
        }
    }

    fun close() {
        conn.close()
        jobs.forEach { it.cancel() }
    }

    fun networkError(cause: Throwable?): TransferException {
        val c = closeCause ?: cause
        return when {
            c is TransferException -> c
            c is SocketTimeoutException -> TransferException(ErrorCode.TIMEOUT, cause = c)
            c is EOFException || c is SSLException || c is java.io.IOException -> TransferException(ErrorCode.NETWORK, cause = c)
            else -> TransferException(ErrorCode.NETWORK, cause = c)
        }
    }
}
