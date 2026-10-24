package com.transferx.core.transfer

import com.transferx.core.protocol.ErrorCode
import com.transferx.core.protocol.FileStatus
import com.transferx.core.storage.FileNameSanitizer
import com.transferx.core.storage.StorageBackend
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

@Serializable
data class ReceiveFileEntry(
    val fileId: String,
    val index: Int,
    /** Sanitized file name. */
    val name: String,
    /** Sanitized relative folder, '/'-separated. */
    val relativeDir: String,
    val size: Long,
    val mimeType: String? = null,
    val modifiedAt: Long? = null,
    val accepted: Boolean,
    val status: FileStatus,
    val error: ErrorCode? = null,
    /** Sender's chunk-list digest, recorded at FILE_END, so a crash during finalization can be recovered. */
    val expectedDigest: String? = null,
    /** Absolute target path chosen at finalization. */
    val finalPath: String? = null,
)

/**
 * Receiver-side session state, stored next to the partial files in
 * `<destination>/.transferx-partial/<sessionId>/manifest.json`.
 */
@Serializable
data class ReceiveManifest(
    val sessionId: String,
    val peer: PeerInfo,
    val transport: TransportKind,
    val chunkSize: Int,
    val destination: String,
    val conflictPolicy: ConflictPolicy,
    val createdAt: Long,
    val updatedAt: Long,
    val state: SessionState,
    val files: List<ReceiveFileEntry>,
)

class ManifestStore(private val fs: StorageBackend) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun partialDir(destination: Path, sessionId: String): Path =
        destination.resolve(FileNameSanitizer.PARTIAL_DIR).resolve(sessionId)

    fun save(dir: Path, manifest: ReceiveManifest) {
        fs.createDirectories(dir)
        fs.writeAtomically(dir.resolve(FILE), json.encodeToString(ReceiveManifest.serializer(), manifest).toByteArray())
    }

    fun load(dir: Path): ReceiveManifest? = try {
        val p = dir.resolve(FILE)
        if (!fs.exists(p)) null else json.decodeFromString(ReceiveManifest.serializer(), String(fs.readBytes(p)))
    } catch (e: Exception) {
        null
    }

    companion object {
        const val FILE = "manifest.json"
    }
}
