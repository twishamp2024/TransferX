package com.transferx.ui

import com.transferx.core.transfer.ConnectTarget
import com.transferx.core.transfer.FileSource
import com.transferx.core.transfer.SkippedEntry
import kotlinx.coroutines.flow.StateFlow

/** Result of a platform file picker: readable sources plus anything that had to be skipped. */
data class PickResult(val sources: List<FileSource>, val skipped: List<SkippedEntry> = emptyList())

enum class CapabilityStatus { AVAILABLE, LIMITED, UNAVAILABLE }

/** One row of the "connection methods" list in Settings: what works on this platform, and why not. */
data class Capability(val method: String, val status: CapabilityStatus, val detail: String)

data class BluetoothPeer(val name: String, val address: String, val bonded: Boolean)

enum class BluetoothState { UNSUPPORTED, OFF, NO_PERMISSION, READY, LISTENING }

/** Implemented only where the OS exposes RFCOMM sockets to apps (Android). */
interface BluetoothBridge {
    val state: StateFlow<BluetoothState>
    fun bondedDevices(): List<BluetoothPeer>
    fun target(peer: BluetoothPeer): ConnectTarget
    fun requestEnable()
    fun startListening()
    fun stopListening()
}

/** Everything the shared UI needs from the host platform. */
interface PlatformBridge {
    val isMobile: Boolean
    val supportsFolderSelection: Boolean
    val bluetooth: BluetoothBridge?
    val capabilities: List<Capability>

    suspend fun pickFiles(): PickResult
    suspend fun pickFolderToSend(): PickResult

    /** Lets the user choose where received files are saved. Returns a path string or null. */
    suspend fun pickDestination(current: String): String?

    fun openFolder(path: String)
    fun copyToClipboard(text: String)
}
