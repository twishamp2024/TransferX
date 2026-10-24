package com.transferx.core.app

import com.transferx.core.discovery.Announcement
import com.transferx.core.discovery.DiscoveryService
import com.transferx.core.discovery.NetUtils
import com.transferx.core.persistence.HistoryStore
import com.transferx.core.persistence.ReceiveIndex
import com.transferx.core.security.DeviceIdentity
import com.transferx.core.security.PairingCodes
import com.transferx.core.security.TrustStore
import com.transferx.core.storage.NioStorageBackend
import com.transferx.core.storage.StorageBackend
import com.transferx.core.transfer.ConflictPolicy
import com.transferx.core.transfer.ConnectTarget
import com.transferx.core.transfer.EngineConfig
import com.transferx.core.transfer.LocalDevice
import com.transferx.core.transfer.LocalFileSource
import com.transferx.core.transfer.ReceiveSettings
import com.transferx.core.transfer.SourceResolver
import com.transferx.core.transfer.TransferEngine
import com.transferx.core.util.AtomicFiles
import com.transferx.core.util.Ids
import com.transferx.core.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class AppSettings(
    val deviceName: String,
    val destination: String,
    /** Announce this device on the network. Turn off on untrusted networks. */
    val discoverable: Boolean = true,
    /** Accept incoming offers at all (each one still needs approval). */
    val receiving: Boolean = true,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
    val port: Int = EngineConfig.DEFAULT_PORT,
    val chunkSizeKiB: Int = 512,
)

class SettingsStore(private val path: Path, defaults: AppSettings) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val _settings = MutableStateFlow(load() ?: defaults)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current: AppSettings get() = _settings.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value).let {
            it.copy(deviceName = it.deviceName.filter { c -> c.code >= 0x20 }.trim().take(64).ifEmpty { current.deviceName })
        }
        _settings.value = next
        runCatching { AtomicFiles.write(path, json.encodeToString(AppSettings.serializer(), next).toByteArray()) }
            .onFailure { Log.w("Settings", "Could not save settings", it) }
    }

    private fun load(): AppSettings? = AtomicFiles.readOrNull(path)?.let {
        // Tolerate a UTF-8 BOM: settings may be hand-edited with Windows tools that add one.
        val text = String(it, Charsets.UTF_8).removePrefix("﻿")
        runCatching { json.decodeFromString(AppSettings.serializer(), text) }
            .onFailure { e -> Log.w("Settings", "settings.json unreadable; using defaults", e) }
            .getOrNull()
    }
}

/** What a scanned QR code or pasted connection link tells us about a receiver. */
data class ConnectionLink(
    val deviceId: String?,
    val name: String?,
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String?,
    val code: String?,
) {
    fun toTarget(): ConnectTarget.Tcp = ConnectTarget.Tcp(hosts, port, fingerprint, code, deviceId)

    companion object {
        const val SCHEME = "transferx://connect"

        fun format(deviceId: String, name: String, hosts: List<String>, port: Int, fingerprint: String, code: String?): String {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            return buildString {
                append(SCHEME).append("?v=1")
                append("&id=").append(enc(deviceId))
                append("&n=").append(enc(name))
                append("&h=").append(enc(hosts.joinToString(",")))
                append("&p=").append(port)
                append("&fp=").append(fingerprint)
                if (code != null) append("&c=").append(code)
            }
        }

        /**
         * Parses a `transferx://connect?…` link, or a plain "host[:port]" typed by the user.
         * Returns null if the input is neither.
         */
        fun parse(input: String, defaultPort: Int = EngineConfig.DEFAULT_PORT): ConnectionLink? {
            val text = input.trim()
            if (text.startsWith(SCHEME)) {
                val query = text.substringAfter('?', "")
                val params = query.split('&').filter { '=' in it }.associate {
                    it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
                val hosts = params["h"]?.split(',')?.map { it.trim() }?.filter { NetUtils.parseLiteral(it) != null }.orEmpty()
                val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                val fp = params["fp"]?.lowercase()?.takeIf { f -> f.length == 64 && f.all { it in "0123456789abcdef" } }
                if (hosts.isEmpty()) return null
                return ConnectionLink(
                    deviceId = params["id"]?.takeIf { Ids.isValid(it) },
                    name = params["n"]?.take(64),
                    hosts = hosts, port = port, fingerprint = fp,
                    code = params["c"]?.let { PairingCodes.normalize(it) },
                )
            }
            val host = text.substringBeforeLast(':').takeIf { ':' in text && !text.contains("::") } ?: text
            val port = if (host != text) text.substringAfterLast(':').toIntOrNull() ?: return null else defaultPort
            if (NetUtils.parseLiteral(host) == null || port !in 1..65535) return null
            return ConnectionLink(null, null, listOf(host), port, null, null)
        }
    }
}

/**
 * One running TransferX installation: identity, stores, transfer engine, and discovery,
 * wired together. Platform front ends (desktop, Android, CLI) create one of these with
 * their own directories and hooks.
 */
class TransferXNode(
    val dataDir: Path,
    val platform: String,
    defaultName: String,
    defaultDestination: Path,
    val storage: StorageBackend = NioStorageBackend(),
    sourceResolver: SourceResolver? = null,
    private val configOverride: EngineConfig? = null,
    acquireMulticastLock: () -> AutoCloseable? = { null },
    extraTransports: List<String> = emptyList(),
    val appVersion: String = APP_VERSION,
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val identity: DeviceIdentity = DeviceIdentity.loadOrCreate(dataDir)
    val settingsStore = SettingsStore(dataDir.resolve("settings.json"), AppSettings(defaultName, defaultDestination.toString()))
    val trust = TrustStore(dataDir.resolve("trusted-devices.json"))
    val history = HistoryStore(dataDir.resolve("history.json"))
    private val transports = listOf("wifi") + extraTransports

    val engine: TransferEngine = TransferEngine(
        config = configOverride ?: EngineConfig(chunkSize = settingsStore.current.chunkSizeKiB.coerceIn(16, 4096) * 1024),
        identity = identity,
        trustStore = trust,
        history = history,
        receiveIndex = ReceiveIndex(dataDir.resolve("receive-index.json")),
        storage = storage,
        localDevice = { LocalDevice(settingsStore.current.deviceName, platform, appVersion, transports) },
        receiveSettings = {
            val s = settingsStore.current
            ReceiveSettings(Paths.get(s.destination), s.receiving, s.conflictPolicy)
        },
        sourceResolver = sourceResolver ?: SourceResolver { ref ->
            if (ref.kind == "file") runCatching { LocalFileSource(Paths.get(ref.location), ref.relativeDir) }.getOrNull() else null
        },
        scope = scope,
    )

    val discovery = DiscoveryService(
        scope = scope,
        self = {
            val s = settingsStore.current
            val port = engine.listeningPort.value
            if (!s.discoverable || port == null) null
            else Announcement(id = identity.deviceId, name = s.deviceName, platform = platform, port = port, fp = identity.fingerprintHex, transports = transports)
        },
        acquireMulticastLock = acquireMulticastLock,
    )

    fun start(listen: Boolean = true, fileLog: Boolean = true) {
        if (fileLog) Log.addFileSink(dataDir.resolve("logs"))
        engine.recoverOnStartup()
        if (listen) engine.startListening(settingsStore.current.port)
        engine.addressResolver = { id -> discovery.addressesOf(id) }
        discovery.start()
        Log.i("Node", "TransferX ${appVersion} started as '${settingsStore.current.deviceName}' ($platform)")
    }

    /** The QR / link payload a sender scans to reach this device. */
    fun connectionLink(includeCode: Boolean = true): String = ConnectionLink.format(
        identity.deviceId, settingsStore.current.deviceName, NetUtils.advertisedAddresses(),
        engine.listeningPort.value ?: settingsStore.current.port, identity.fingerprintHex,
        if (includeCode) engine.pairing.activeCode().code else null,
    )

    override fun close() {
        discovery.stop()
        engine.close()
        scope.cancel()
    }

    companion object {
        const val APP_VERSION = "1.0.0"
    }
}
