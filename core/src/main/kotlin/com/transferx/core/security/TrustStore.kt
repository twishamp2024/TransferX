package com.transferx.core.security

import com.transferx.core.util.AtomicFiles
import com.transferx.core.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path

@Serializable
data class TrustedDevice(
    val deviceId: String,
    val name: String,
    val platform: String,
    /** SHA-256 of the device certificate, hex. This, not the claimed device id, is the identity. */
    val fingerprint: String,
    val pairedAt: Long,
    val lastSeenAt: Long = pairedAt,
)

/** Devices this installation has paired with. Persisted as JSON with atomic writes. */
class TrustStore(private val path: Path?) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<TrustedDevice>> = _devices.asStateFlow()

    fun isTrusted(fingerprint: String): Boolean = _devices.value.any { it.fingerprint == fingerprint }

    fun byFingerprint(fingerprint: String): TrustedDevice? = _devices.value.firstOrNull { it.fingerprint == fingerprint }

    fun byDeviceId(deviceId: String): TrustedDevice? = _devices.value.firstOrNull { it.deviceId == deviceId }

    @Synchronized
    fun trust(device: TrustedDevice) {
        // Re-pairing a device id with a new certificate replaces the old entry; the old
        // certificate is no longer accepted.
        val rest = _devices.value.filter { it.fingerprint != device.fingerprint && it.deviceId != device.deviceId }
        save(rest + device)
    }

    @Synchronized
    fun touch(fingerprint: String, name: String, now: Long = System.currentTimeMillis()) {
        val list = _devices.value
        if (list.none { it.fingerprint == fingerprint }) return
        save(list.map { if (it.fingerprint == fingerprint) it.copy(name = name, lastSeenAt = now) else it })
    }

    @Synchronized
    fun remove(fingerprint: String) = save(_devices.value.filter { it.fingerprint != fingerprint })

    private fun load(): List<TrustedDevice> {
        val bytes = path?.let { AtomicFiles.readOrNull(it) } ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(TrustedDevice.serializer()), String(bytes, Charsets.UTF_8))
        }.onFailure { Log.w("TrustStore", "Trust store unreadable; starting empty", it) }.getOrDefault(emptyList())
    }

    private fun save(list: List<TrustedDevice>) {
        _devices.value = list
        path?.let {
            AtomicFiles.write(it, json.encodeToString(ListSerializer(TrustedDevice.serializer()), list).toByteArray())
        }
    }
}
