package com.transferx.core.persistence

import com.transferx.core.transfer.TransferRecord
import com.transferx.core.util.AtomicFiles
import com.transferx.core.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Path

private val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/**
 * Transfer history for both directions, newest first. Written atomically, so a crash
 * leaves the previous version intact. Records for live sessions are updated on status
 * changes, not on every progress tick.
 */
class HistoryStore(private val path: Path?, private val maxRecords: Int = 500) {
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<TransferRecord>> = _records.asStateFlow()

    fun get(id: String): TransferRecord? = _records.value.firstOrNull { it.snapshot.id == id }

    @Synchronized
    fun upsert(record: TransferRecord) {
        val rest = _records.value.filter { it.snapshot.id != record.snapshot.id }
        save((listOf(record) + rest).sortedByDescending { it.snapshot.createdAt }.take(maxRecords))
    }

    @Synchronized
    fun update(id: String, transform: (TransferRecord) -> TransferRecord) {
        val current = get(id) ?: return
        upsert(transform(current))
    }

    @Synchronized
    fun remove(id: String) = save(_records.value.filter { it.snapshot.id != id })

    @Synchronized
    fun clearFinished() = save(_records.value.filter { !it.snapshot.state.terminal })

    private fun load(): List<TransferRecord> {
        val bytes = path?.let { AtomicFiles.readOrNull(it) } ?: return emptyList()
        return runCatching {
            storeJson.decodeFromString(ListSerializer(TransferRecord.serializer()), String(bytes, Charsets.UTF_8))
        }.onFailure { Log.w("History", "History unreadable; starting empty", it) }.getOrDefault(emptyList())
    }

    private fun save(list: List<TransferRecord>) {
        _records.value = list
        val p = path ?: return
        try {
            AtomicFiles.write(p, storeJson.encodeToString(ListSerializer(TransferRecord.serializer()), list).toByteArray())
        } catch (e: Exception) {
            // History is informational; a failed save (e.g. app disk full) must not break transfers.
            Log.w("History", "Could not save history", e)
        }
    }
}

/** sessionId → partial directory, for receive sessions that may still be resumed. */
class ReceiveIndex(private val path: Path?) {
    private var map: Map<String, String> = load()

    @Synchronized fun get(sessionId: String): Path? = map[sessionId]?.let { java.nio.file.Paths.get(it) }

    @Synchronized fun all(): Map<String, Path> = map.mapValues { java.nio.file.Paths.get(it.value) }

    @Synchronized fun put(sessionId: String, dir: Path) = save(map + (sessionId to dir.toAbsolutePath().toString()))

    @Synchronized fun remove(sessionId: String) = save(map - sessionId)

    private fun load(): Map<String, String> {
        val bytes = path?.let { AtomicFiles.readOrNull(it) } ?: return emptyMap()
        return runCatching {
            storeJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), String(bytes))
        }.getOrDefault(emptyMap())
    }

    private fun save(m: Map<String, String>) {
        map = m
        val p = path ?: return
        try {
            AtomicFiles.write(p, storeJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), m).toByteArray())
        } catch (e: Exception) {
            Log.w("ReceiveIndex", "Could not save receive index", e)
        }
    }
}
