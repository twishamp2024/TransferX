package com.transferx.core.storage

import kotlinx.serialization.Serializable
import java.nio.file.Path

data class PlannedFile(
    val fileId: String,
    val size: Long,
    /** Bytes already held in a verified partial file (resume), which needn't be written again. */
    val alreadyHave: Long = 0,
)

@Serializable
data class PreflightResult(
    val destination: String,
    val writable: Boolean,
    /** Usable bytes reported by the OS, or null if it couldn't be measured. */
    val availableBytes: Long?,
    /** Bytes still to be written for all requested files, including metadata overhead and safety reserve. */
    val requiredBytes: Long,
    /** Space promised to other transfers that are still writing to the same volume. */
    val committedToOtherTransfers: Long,
    val fittingFileIds: List<String>,
    val nonFittingFileIds: List<String>,
    val tooLargeForFilesystem: List<String>,
    val filesystemType: String?,
) {
    val measurementUnknown: Boolean get() = availableBytes == null
    val sufficient: Boolean get() = writable && nonFittingFileIds.isEmpty() && tooLargeForFilesystem.isEmpty()
    val shortfallBytes: Long?
        get() = availableBytes?.let { (requiredBytes + committedToOtherTransfers - it).coerceAtLeast(0) }
}

/**
 * Pre-transfer storage validation. The result is advisory: free space can change at any
 * moment, so passing preflight doesn't guarantee the transfer will fit. Running out
 * mid-transfer is handled separately by the receiver session.
 */
class StoragePreflight(
    private val backend: StorageBackend,
    private val reserveBytes: Long,
    private val chunkSize: Int,
) {
    fun overheadFor(size: Long): Long {
        val chunks = (size + chunkSize - 1) / chunkSize
        return chunks * 32 + 1024 // chunk-hash log + checkpoint file
    }

    fun check(destination: Path, files: List<PlannedFile>, committedToOthers: Long = 0): PreflightResult {
        val writable = backend.canWrite(destination)
        val available = backend.usableSpace(destination)
        val fsType = backend.fileStoreType(destination)
        val maxFileSize = maxFileSizeFor(fsType)

        val tooLarge = files.filter { it.size > maxFileSize }.map { it.fileId }
        val candidates = files.filter { it.fileId !in tooLarge }
        val manifestOverhead = 8 * 1024L + files.size * 512L

        val needs = candidates.associate { it.fileId to (it.size - it.alreadyHave).coerceAtLeast(0) + overheadFor(it.size) }
        val required = needs.values.sum() + manifestOverhead + reserveBytes

        val fitting = mutableListOf<String>()
        val nonFitting = mutableListOf<String>()
        if (available == null) {
            fitting += candidates.map { it.fileId } // can't tell; the UI warns that space is unknown
        } else {
            var budget = available - reserveBytes - manifestOverhead - committedToOthers
            for (f in candidates) {
                val need = needs.getValue(f.fileId)
                if (need <= budget) {
                    fitting += f.fileId
                    budget -= need
                } else {
                    nonFitting += f.fileId
                }
            }
        }
        return PreflightResult(
            destination = destination.toString(),
            writable = writable,
            availableBytes = available,
            requiredBytes = required,
            committedToOtherTransfers = committedToOthers,
            fittingFileIds = if (writable) fitting else emptyList(),
            nonFittingFileIds = if (writable) nonFitting else files.map { it.fileId },
            tooLargeForFilesystem = tooLarge,
            filesystemType = fsType,
        )
    }

    /** True if [bytes] more can probably be written now. Unknown space counts as "try". */
    fun hasRoomFor(destination: Path, bytes: Long): Pair<Boolean, Long?> {
        val available = backend.usableSpace(destination) ?: return true to null
        return (available - reserveBytes >= bytes) to available
    }

    companion object {
        private const val FAT_MAX = 4L * 1024 * 1024 * 1024 - 1

        fun maxFileSizeFor(fsType: String?): Long {
            val t = fsType?.lowercase() ?: return Long.MAX_VALUE
            return if (t in setOf("vfat", "fat", "fat12", "fat16", "fat32", "msdos")) FAT_MAX else Long.MAX_VALUE
        }
    }
}
