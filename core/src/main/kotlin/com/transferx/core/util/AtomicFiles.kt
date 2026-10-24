package com.transferx.core.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

object AtomicFiles {
    /**
     * Writes [bytes] to [target] so that readers see either the old or the new content,
     * never a torn mix: write a sibling temp file, fsync it, then rename over the target.
     */
    @Throws(IOException::class)
    fun write(target: Path, bytes: ByteArray) {
        val dir = target.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = dir.resolve(".${target.fileName}.${Ids.newId()}.tmp")
        try {
            FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun readOrNull(path: Path): ByteArray? = try {
        if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
    } catch (e: IOException) {
        null
    }
}
