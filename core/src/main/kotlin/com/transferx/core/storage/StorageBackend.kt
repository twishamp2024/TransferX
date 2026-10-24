package com.transferx.core.storage

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Random-access writer for a partial file. */
interface PartialWriter : Closeable {
    fun write(position: Long, data: ByteArray, offset: Int, length: Int)
    fun truncate(size: Long)
    fun size(): Long

    /** Flushes written data to stable storage (fsync). */
    fun force()
}

/**
 * Every receiver-side filesystem operation goes through this interface. Production code
 * uses [NioStorageBackend]. Tests substitute a quota/fault-injecting implementation to
 * produce deterministic disk-full and permission failures.
 */
interface StorageBackend {
    /** Usable bytes at [dir], or null if the platform can't tell us. */
    fun usableSpace(dir: Path): Long?

    /** Filesystem type name (e.g. "NTFS", "vfat"), or null if unknown. */
    fun fileStoreType(dir: Path): String?

    fun exists(path: Path): Boolean
    fun isDirectory(path: Path): Boolean
    fun isSymbolicLink(path: Path): Boolean
    fun size(path: Path): Long
    fun createDirectories(dir: Path)
    fun deleteIfExists(path: Path): Boolean
    fun list(dir: Path): List<Path>
    fun lastModified(path: Path): Long

    fun openWriter(path: Path): PartialWriter
    fun openReader(path: Path): InputStream

    /** Appends bytes to [path], creating it if needed. Used for the chunk-hash log. */
    fun append(path: Path, data: ByteArray)

    /** Atomically replaces [path] with [bytes]. */
    fun writeAtomically(path: Path, bytes: ByteArray)
    fun readBytes(path: Path): ByteArray

    /**
     * Moves [source] to [target], atomically where the platform supports it.
     * Throws [FileAlreadyExistsException] if [target] exists and [replace] is false.
     */
    fun move(source: Path, target: Path, replace: Boolean)
    fun setLastModified(path: Path, millis: Long)

    /** True if a file can be created in [dir] right now (probes with a temporary file). */
    fun canWrite(dir: Path): Boolean
}

open class NioStorageBackend : StorageBackend {
    override fun usableSpace(dir: Path): Long? {
        val existing = nearestExisting(dir) ?: return null
        val fromStore = try {
            Files.getFileStore(existing).usableSpace
        } catch (e: IOException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        } catch (e: SecurityException) {
            null
        }
        if (fromStore != null && fromStore >= 0) return fromStore
        // File.getUsableSpace returns 0 both for "full" and "unknown"; treat 0 as unknown
        // here because the FileStore path already failed.
        val legacy = File(existing.toString()).usableSpace
        return if (legacy > 0) legacy else null
    }

    override fun fileStoreType(dir: Path): String? {
        val existing = nearestExisting(dir) ?: return null
        return try {
            Files.getFileStore(existing).type()
        } catch (e: Exception) {
            null
        }
    }

    private fun nearestExisting(dir: Path): Path? {
        var p: Path? = dir.toAbsolutePath()
        while (p != null && !Files.exists(p)) p = p.parent
        return p
    }

    override fun exists(path: Path) = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    override fun isDirectory(path: Path) = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
    override fun isSymbolicLink(path: Path) = Files.isSymbolicLink(path)
    override fun size(path: Path) = Files.size(path)
    override fun createDirectories(dir: Path) {
        Files.createDirectories(dir)
    }

    override fun deleteIfExists(path: Path) = Files.deleteIfExists(path)
    override fun list(dir: Path): List<Path> = Files.list(dir).use { s -> s.collect(java.util.stream.Collectors.toList()) }
    override fun lastModified(path: Path): Long = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()

    override fun openWriter(path: Path): PartialWriter = ChannelWriter(
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ),
    )

    override fun openReader(path: Path): InputStream = Files.newInputStream(path)

    override fun append(path: Path, data: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
            val buf = ByteBuffer.wrap(data)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(false)
        }
    }

    override fun writeAtomically(path: Path, bytes: ByteArray) = com.transferx.core.util.AtomicFiles.write(path, bytes)
    override fun readBytes(path: Path): ByteArray = Files.readAllBytes(path)

    override fun move(source: Path, target: Path, replace: Boolean) {
        val opts = buildList {
            add(StandardCopyOption.ATOMIC_MOVE)
            if (replace) add(StandardCopyOption.REPLACE_EXISTING)
        }.toTypedArray()
        if (!replace && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target.toString())
        try {
            Files.move(source, target, *opts)
        } catch (e: AtomicMoveNotSupportedException) {
            // Cross-volume: a copy+delete. The source is a verified partial, so a crash
            // mid-copy leaves the partial intact for recovery.
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) else Files.move(source, target)
        }
    }

    override fun setLastModified(path: Path, millis: Long) {
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(millis))
    }

    override fun canWrite(dir: Path): Boolean = try {
        Files.createDirectories(dir)
        val probe = Files.createTempFile(dir, ".transferx-probe", ".tmp")
        Files.deleteIfExists(probe)
        true
    } catch (e: Exception) {
        false
    }

    private class ChannelWriter(private val ch: FileChannel) : PartialWriter {
        override fun write(position: Long, data: ByteArray, offset: Int, length: Int) {
            val buf = ByteBuffer.wrap(data, offset, length)
            var pos = position
            while (buf.hasRemaining()) pos += ch.write(buf, pos)
        }

        override fun truncate(size: Long) {
            ch.truncate(size)
        }

        override fun size(): Long = ch.size()
        override fun force() = ch.force(false)
        override fun close() = ch.close()
    }
}
