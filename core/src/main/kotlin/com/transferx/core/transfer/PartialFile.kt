package com.transferx.core.transfer

import com.transferx.core.storage.PartialWriter
import com.transferx.core.storage.StorageBackend
import com.transferx.core.util.Hashing
import com.transferx.core.util.Hex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path

/**
 * The receiver's on-disk state for one incoming file:
 *
 * - `<id>.part`   the data written so far (never under the final name)
 * - `<id>.hashes` append-only log of 32-byte SHA-256 chunk hashes, as sent by the sender
 *                 and verified against the received bytes
 * - `<id>.ckpt`   small JSON checkpoint, replaced atomically:
 *                 { verifiedOffset, hashCount, digest of the hash log prefix }
 *
 * Invariant: bytes [0, verifiedOffset) of the .part file were fsynced *before* the
 * checkpoint naming them was written, and each chunk's hash is in the log. Anything
 * after verifiedOffset is untrusted and gets truncated on recovery.
 */
class PartialFile(
    private val fs: StorageBackend,
    dir: Path,
    val fileId: String,
    val size: Long,
    val chunkSize: Int,
) {
    val partPath: Path = dir.resolve("$fileId.part")
    private val hashesPath: Path = dir.resolve("$fileId.hashes")
    private val ckptPath: Path = dir.resolve("$fileId.ckpt")

    @Serializable
    private data class Checkpoint(
        val v: Int = 1,
        val chunkSize: Int,
        val size: Long,
        val verifiedOffset: Long,
        val hashCount: Int,
        val hashesDigest: String,
    )

    data class Validation(val verifiedOffset: Long, val prefixDigest: String, val repaired: Boolean, val reason: String?)

    var verifiedOffset: Long = 0; private set
    var writtenOffset: Long = 0; private set
    private var writer: PartialWriter? = null
    private val pendingHashes = ArrayList<ByteArray>()
    private var committedHashes = 0
    private var hashLogDigest = Hashing.sha256() // running digest over committed hashes

    val totalChunks: Long get() = if (size == 0L) 0 else (size + chunkSize - 1) / chunkSize

    /**
     * Loads and verifies existing partial data by re-hashing every chunk listed in the
     * checkpoint. The partial file is truncated to the longest verified prefix.
     */
    fun loadAndValidate(): Validation {
        close()
        val ckpt = readCheckpoint()
        if (ckpt == null || ckpt.chunkSize != chunkSize || ckpt.size != size) {
            val hadData = fs.exists(partPath) && fs.size(partPath) > 0
            resetFiles()
            return Validation(0, emptyDigest(), hadData, if (hadData) "checkpoint missing or inconsistent" else null)
        }
        val hashBytes = if (fs.exists(hashesPath)) fs.readBytes(hashesPath) else ByteArray(0)
        val available = minOf(ckpt.hashCount, hashBytes.size / 32)
        val logDigest = Hashing.sha256().apply { update(hashBytes, 0, available * 32) }.digest()
        if (available != ckpt.hashCount || Hex.encode(logDigest) != ckpt.hashesDigest) {
            resetFiles()
            return Validation(0, emptyDigest(), true, "chunk hash log damaged")
        }
        val partSize = if (fs.exists(partPath)) fs.size(partPath) else 0
        var good = 0
        var reason: String? = null
        if (available > 0) {
            fs.openReader(partPath).use { input ->
                val buf = ByteArray(chunkSize)
                for (i in 0 until available) {
                    val start = i.toLong() * chunkSize
                    val len = minOf(chunkSize.toLong(), size - start).toInt()
                    if (start + len > partSize) {
                        reason = "partial file shorter than checkpoint"; break
                    }
                    readFully(input, buf, len)
                    val h = Hashing.sha256(buf, 0, len)
                    if (!Hashing.constantTimeEquals(h, hashBytes.copyOfRange(i * 32, i * 32 + 32))) {
                        reason = "chunk $i failed verification"; break
                    }
                    good++
                }
            }
        }
        val offset = minOf(good.toLong() * chunkSize, size)
        val repaired = good != ckpt.hashCount || partSize != offset
        // Rebuild in-memory state from the verified prefix.
        hashLogDigest = Hashing.sha256().apply { update(hashBytes, 0, good * 32) }
        committedHashes = good
        verifiedOffset = offset
        writtenOffset = offset
        pendingHashes.clear()
        if (repaired) {
            openWriter().truncate(offset)
            truncateHashLog(good)
            writeCheckpoint()
        }
        return Validation(offset, prefixDigestOf(hashBytes, good), repaired && reason != null, reason)
    }

    /** Starts from nothing (new file, or a restart requested by either side). */
    fun resetFiles() {
        close()
        fs.deleteIfExists(partPath)
        fs.deleteIfExists(hashesPath)
        fs.deleteIfExists(ckptPath)
        verifiedOffset = 0
        writtenOffset = 0
        committedHashes = 0
        pendingHashes.clear()
        hashLogDigest = Hashing.sha256()
    }

    /** Writes one chunk. Its hash must already be verified against the data by the caller. */
    @Throws(IOException::class)
    fun writeChunk(offset: Long, data: ByteArray, length: Int, chunkHash: ByteArray) {
        check(offset == writtenOffset) { "non-sequential write" }
        openWriter().write(offset, data, 0, length)
        writtenOffset += length
        pendingHashes += chunkHash
    }

    /** Makes everything written so far durable and records it in the checkpoint. */
    @Throws(IOException::class)
    fun checkpoint() {
        if (writtenOffset == verifiedOffset && pendingHashes.isEmpty() && fs.exists(ckptPath)) return
        openWriter().force()
        if (pendingHashes.isNotEmpty()) {
            val bytes = ByteArray(pendingHashes.size * 32)
            pendingHashes.forEachIndexed { i, h -> System.arraycopy(h, 0, bytes, i * 32, 32) }
            fs.append(hashesPath, bytes)
            val newDigest = hashLogDigest.clone() as java.security.MessageDigest
            newDigest.update(bytes)
            val newCount = committedHashes + pendingHashes.size
            val newOffset = writtenOffset
            writeCheckpointFor(newOffset, newCount, newDigest)
            hashLogDigest = newDigest
            committedHashes = newCount
            verifiedOffset = newOffset
            pendingHashes.clear()
        } else {
            writeCheckpoint()
        }
    }

    /**
     * Discards everything after the last checkpoint. Called after a write failure, so
     * the partial file never contains unverified bytes beyond its checkpoint.
     */
    fun rollbackToCheckpoint() {
        pendingHashes.clear()
        writtenOffset = verifiedOffset
        try {
            openWriter().truncate(verifiedOffset)
        } catch (_: IOException) {
            // Even if truncation fails, recovery re-validates from the checkpoint.
        }
        try {
            truncateHashLog(committedHashes)
        } catch (_: IOException) {
        }
        close()
    }

    /** SHA-256 over the committed chunk hashes, i.e. a digest of the verified prefix [0, verifiedOffset). */
    fun prefixDigest(): String = Hex.encode((hashLogDigest.clone() as java.security.MessageDigest).digest())

    /** Chunk-list digest of the partial file as re-read from disk (the final integrity check). */
    fun digestOnDisk(): String {
        close()
        return fs.openReader(partPath).use { com.transferx.core.util.FileDigest.of(it, chunkSize) }
    }

    fun close() {
        try {
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
    }

    /** Removes the hash log and checkpoint after the file was finalized. */
    fun deleteMetadata() {
        close()
        fs.deleteIfExists(hashesPath)
        fs.deleteIfExists(ckptPath)
    }

    fun deleteAll() = resetFiles()

    private fun openWriter(): PartialWriter = writer ?: fs.openWriter(partPath).also { writer = it }

    private fun writeCheckpoint() = writeCheckpointFor(verifiedOffset, committedHashes, hashLogDigest)

    private fun writeCheckpointFor(offset: Long, count: Int, digest: java.security.MessageDigest) {
        val d = (digest.clone() as java.security.MessageDigest).digest()
        val ck = Checkpoint(chunkSize = chunkSize, size = size, verifiedOffset = offset, hashCount = count, hashesDigest = Hex.encode(d))
        fs.writeAtomically(ckptPath, json.encodeToString(Checkpoint.serializer(), ck).toByteArray())
    }

    private fun readCheckpoint(): Checkpoint? = try {
        if (!fs.exists(ckptPath)) null else json.decodeFromString(Checkpoint.serializer(), String(fs.readBytes(ckptPath)))
    } catch (e: Exception) {
        null
    }

    private fun truncateHashLog(count: Int) {
        if (!fs.exists(hashesPath)) return
        fs.openWriter(hashesPath).use { it.truncate(count.toLong() * 32) }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) throw IOException("unexpected end of partial file")
            read += n
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun emptyDigest(): String = Hex.encode(Hashing.sha256().digest())

        fun prefixDigestOf(hashLog: ByteArray, count: Int): String =
            Hex.encode(Hashing.sha256().apply { update(hashLog, 0, count * 32) }.digest())
    }
}
