package com.transferx.core.transfer

import kotlinx.serialization.Serializable
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Serializable pointer to a source file, so a sender can reopen it to resume or retry. */
@Serializable
data class SourceRef(
    /** "file" for filesystem paths; platforms add their own kinds (e.g. "content" on Android). */
    val kind: String,
    val location: String,
    val relativeDir: String = "",
    val name: String,
)

/** A readable file on the sending device. Implementations must stream, never buffer whole files. */
interface FileSource {
    val ref: SourceRef
    val name: String get() = ref.name
    val relativeDir: String get() = ref.relativeDir
    val size: Long
    val mimeType: String?
    val modifiedAt: Long?

    /** Opens the file positioned at [offset]. */
    @Throws(IOException::class)
    fun open(offset: Long): InputStream

    /** Current (size, modifiedAt) on disk, to detect modification during a transfer. Null if unavailable. */
    fun currentStat(): Pair<Long, Long?>?
}

fun interface SourceResolver {
    fun resolve(ref: SourceRef): FileSource?
}

class LocalFileSource(private val path: Path, relativeDir: String = "") : FileSource {
    override val ref = SourceRef("file", path.toAbsolutePath().toString(), relativeDir, path.fileName.toString())
    override val size: Long = Files.size(path)
    override val modifiedAt: Long? = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()
    override val mimeType: String? = guessMime(path.fileName.toString())

    override fun open(offset: Long): InputStream {
        val ch = FileChannel.open(path, StandardOpenOption.READ)
        try {
            ch.position(offset)
        } catch (e: IOException) {
            ch.close()
            throw e
        }
        return Channels.newInputStream(ch)
    }

    override fun currentStat(): Pair<Long, Long?>? = runCatching {
        Files.size(path) to Files.getLastModifiedTime(path).toMillis()
    }.getOrNull()

    companion object {
        fun guessMime(name: String): String? = runCatching { URLConnection.guessContentTypeFromName(name) }.getOrNull()
            ?: when (name.substringAfterLast('.', "").lowercase()) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "flac" -> "audio/flac"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "apk" -> "application/vnd.android.package-archive"
                "heic" -> "image/heic"
                "webp" -> "image/webp"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                else -> null
            }
    }
}

data class SkippedEntry(val path: String, val reason: String)

data class ExpandedSelection(val sources: List<FileSource>, val skipped: List<SkippedEntry>)

/**
 * Expands user-selected files and folders. Symbolic links are never followed, so a
 * link can't pull in files outside the selection. Special files (devices, FIFOs,
 * sockets) are skipped.
 */
object LocalSelection {
    fun expand(paths: List<Path>, maxFiles: Int = 10_000): ExpandedSelection {
        val sources = mutableListOf<FileSource>()
        val skipped = mutableListOf<SkippedEntry>()
        for (p in paths) {
            when {
                Files.isSymbolicLink(p) -> skipped += SkippedEntry(p.toString(), "symbolic link")
                Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) -> addFile(p, "", sources, skipped)
                Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> walk(p, sources, skipped, maxFiles)
                Files.exists(p, LinkOption.NOFOLLOW_LINKS) -> skipped += SkippedEntry(p.toString(), "not a regular file")
                else -> skipped += SkippedEntry(p.toString(), "not found")
            }
            if (sources.size >= maxFiles) {
                skipped += SkippedEntry(p.toString(), "too many files (limit $maxFiles)")
                break
            }
        }
        return ExpandedSelection(sources, skipped)
    }

    private fun addFile(p: Path, relDir: String, out: MutableList<FileSource>, skipped: MutableList<SkippedEntry>) {
        try {
            out += LocalFileSource(p, relDir)
        } catch (e: IOException) {
            skipped += SkippedEntry(p.toString(), "unreadable")
        } catch (e: SecurityException) {
            skipped += SkippedEntry(p.toString(), "permission denied")
        }
    }

    private fun walk(root: Path, out: MutableList<FileSource>, skipped: MutableList<SkippedEntry>, maxFiles: Int) {
        val base = root.fileName?.toString() ?: "folder"
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (out.size >= maxFiles) return FileVisitResult.TERMINATE
                when {
                    attrs.isSymbolicLink -> skipped += SkippedEntry(file.toString(), "symbolic link")
                    attrs.isRegularFile -> {
                        val rel = root.relativize(file.parent).toString().replace('\\', '/')
                        val dir = if (rel.isEmpty()) base else "$base/$rel"
                        addFile(file, dir, out, skipped)
                    }
                    else -> skipped += SkippedEntry(file.toString(), "not a regular file")
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                skipped += SkippedEntry(file.toString(), "unreadable")
                return FileVisitResult.CONTINUE
            }
        })
    }
}
