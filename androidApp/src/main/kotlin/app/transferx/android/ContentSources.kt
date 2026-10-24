package app.transferx.android

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.transferx.core.transfer.FileSource
import com.transferx.core.transfer.SkippedEntry
import com.transferx.core.transfer.SourceRef
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A file chosen through the Storage Access Framework (content:// URI). There's no
 * filesystem path: it's opened as a stream positioned at the requested offset.
 */
class ContentUriSource private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val ref: SourceRef,
    override val size: Long,
    override val mimeType: String?,
    override val modifiedAt: Long?,
) : FileSource {

    override fun open(offset: Long): InputStream {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("cannot open $uri")
        val stream = FileInputStream(pfd.fileDescriptor)
        try {
            stream.channel.position(offset) // seekable for real files
        } catch (e: Exception) {
            // Some providers return pipes: fall back to skipping.
            stream.close(); pfd.close()
            val s = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            var skipped = 0L
            while (skipped < offset) {
                val n = s.skip(offset - skipped)
                if (n <= 0) throw IOException("cannot seek in $uri")
                skipped += n
            }
            return s
        }
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                stream.close(); pfd.close()
            }
        }
    }

    override fun currentStat(): Pair<Long, Long?>? = query(resolver, uri)?.let { it.size to it.modified }

    private data class Meta(val name: String, val size: Long, val modified: Long?)

    companion object {
        /** Creates a source, or null if the provider doesn't report a size (we need it for preflight). */
        fun from(resolver: ContentResolver, uri: Uri, relativeDir: String = ""): ContentUriSource? {
            val meta = query(resolver, uri) ?: return null
            if (meta.size < 0) return null
            val ref = SourceRef("content", uri.toString(), relativeDir, meta.name)
            return ContentUriSource(resolver, uri, ref, meta.size, resolver.getType(uri), meta.modified)
        }

        private fun query(resolver: ContentResolver, uri: Uri): Meta? = try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val name = if (nameIdx >= 0) c.getString(nameIdx) else uri.lastPathSegment ?: "file"
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1
                val mod = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else null
                Meta(name, size, mod)
            }
        } catch (e: SecurityException) {
            null
        }

        /** Keeps read access across restarts so interrupted transfers can resume. */
        fun persist(resolver: ContentResolver, uri: Uri) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        /**
         * Walks a folder chosen with ACTION_OPEN_DOCUMENT_TREE. Virtual (non-openable)
         * documents are skipped; there are no symbolic links in the document model.
         */
        fun expandTree(resolver: ContentResolver, tree: Uri, maxFiles: Int = 10_000): Pair<List<FileSource>, List<SkippedEntry>> {
            val out = mutableListOf<FileSource>()
            val skipped = mutableListOf<SkippedEntry>()
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val rootName = query(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId))?.name ?: "Folder"
            fun walk(docId: String, rel: String) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                val cols = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
                )
                resolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext() && out.size < maxFiles) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: "item"
                        val mime = c.getString(2)
                        val flags = c.getInt(3)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(id, "$rel/$name")
                        } else if (flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) {
                            skipped += SkippedEntry(name, "virtual document")
                        } else {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            from(resolver, uri, rel)?.let { out += it } ?: run { skipped += SkippedEntry(name, "unreadable") }
                        }
                    }
                }
            }
            walk(rootId, rootName)
            return out to skipped
        }
    }
}
