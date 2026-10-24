package com.transferx.core.protocol

import com.transferx.core.util.Hashing
import kotlinx.serialization.SerializationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed class Frame {
    data class Control(val message: Message) : Frame()

    /** [data] is owned by the receiver of the frame; [length] valid bytes start at index 0. */
    class Data(val fileIndex: Int, val offset: Long, val chunkHash: ByteArray, val data: ByteArray, val length: Int) :
        Frame()
}

/**
 * Length-prefixed framing over an already-authenticated, encrypted byte stream.
 *
 * ```
 * frame   := u32 length | u8 type | payload          (length = 1 + payload size, big-endian)
 * CONTROL := type 0x01, payload = UTF-8 JSON Message
 * DATA    := type 0x02, payload = i32 fileIndex | i64 offset | 32-byte SHA-256(chunk) | chunk bytes
 * ```
 *
 * Frame sizes are bounded so a malicious peer can't make us allocate unbounded memory.
 */
class FramedConnection(
    input: InputStream,
    output: OutputStream,
    private val maxControlBytes: Int = 8 * 1024 * 1024,
    private val maxChunkBytes: Int = 4 * 1024 * 1024,
    private val onClose: () -> Unit = {},
) : Closeable {
    private val inp = DataInputStream(BufferedInputStream(input, 64 * 1024))
    private val out = DataOutputStream(BufferedOutputStream(output, 64 * 1024))
    private val writeLock = ReentrantLock()
    private val closed = AtomicBoolean(false)

    @Volatile var bytesSent: Long = 0; private set
    @Volatile var bytesReceived: Long = 0; private set

    val isClosed: Boolean get() = closed.get()

    fun send(message: Message) {
        val json = ProtocolJson.encodeToString(Message.serializer(), message).toByteArray(Charsets.UTF_8)
        if (json.size > maxControlBytes) throw TransferException(ErrorCode.PROTOCOL_ERROR, "control message too large")
        writeLock.withLock {
            out.writeInt(1 + json.size)
            out.writeByte(TYPE_CONTROL)
            out.write(json)
            out.flush()
            bytesSent += 5 + json.size
        }
    }

    fun sendData(fileIndex: Int, offset: Long, chunkHash: ByteArray, data: ByteArray, length: Int) {
        require(chunkHash.size == Hashing.SHA256_LEN)
        require(length in 0..maxChunkBytes)
        writeLock.withLock {
            out.writeInt(1 + DATA_HEADER + length)
            out.writeByte(TYPE_DATA)
            out.writeInt(fileIndex)
            out.writeLong(offset)
            out.write(chunkHash)
            out.write(data, 0, length)
            out.flush()
            bytesSent += 5 + DATA_HEADER + length
        }
    }

    /** Blocks until a full frame arrives. Throws [EOFException] on orderly close. */
    fun readFrame(): Frame {
        val length = inp.readInt()
        if (length < 1) throw TransferException(ErrorCode.PROTOCOL_ERROR, "invalid frame length")
        val type = inp.readUnsignedByte()
        val payload = length - 1
        bytesReceived += 4L + length
        return when (type) {
            TYPE_CONTROL -> {
                if (payload > maxControlBytes) throw TransferException(ErrorCode.PROTOCOL_ERROR, "control frame too large")
                val bytes = ByteArray(payload)
                inp.readFully(bytes)
                val message = try {
                    ProtocolJson.decodeFromString(Message.serializer(), String(bytes, Charsets.UTF_8))
                } catch (e: SerializationException) {
                    throw TransferException(ErrorCode.PROTOCOL_ERROR, "malformed control message", e)
                } catch (e: IllegalArgumentException) {
                    throw TransferException(ErrorCode.PROTOCOL_ERROR, "malformed control message", e)
                }
                Frame.Control(message)
            }
            TYPE_DATA -> {
                val dataLen = payload - DATA_HEADER
                if (dataLen < 0 || dataLen > maxChunkBytes) throw TransferException(ErrorCode.PROTOCOL_ERROR, "data frame size")
                val fileIndex = inp.readInt()
                val offset = inp.readLong()
                val hash = ByteArray(Hashing.SHA256_LEN)
                inp.readFully(hash)
                val data = ByteArray(dataLen)
                inp.readFully(data)
                Frame.Data(fileIndex, offset, hash, data, dataLen)
            }
            else -> throw TransferException(ErrorCode.PROTOCOL_ERROR, "unknown frame type $type")
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                onClose()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val TYPE_CONTROL = 0x01
        const val TYPE_DATA = 0x02
        const val DATA_HEADER = 4 + 8 + Hashing.SHA256_LEN
    }
}
