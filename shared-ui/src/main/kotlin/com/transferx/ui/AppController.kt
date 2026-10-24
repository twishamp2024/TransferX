package com.transferx.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.transferx.core.app.ConnectionLink
import com.transferx.core.app.TransferXNode
import com.transferx.core.discovery.DiscoveredDevice
import com.transferx.core.transfer.ConflictPolicy
import com.transferx.core.transfer.ConnectTarget
import com.transferx.core.transfer.Direction
import com.transferx.core.transfer.FileSource
import com.transferx.core.transfer.OfferDecision
import com.transferx.core.transfer.SessionSnapshot
import com.transferx.core.transfer.SkippedEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.nio.file.Paths

enum class Screen(val title: String) {
    HOME("Home"),
    SEND("Send"),
    RECEIVE("Receive"),
    DEVICES("Nearby"),
    TRANSFER("Transfer"),
    HISTORY("History"),
    SETTINGS("Settings"),
}

/**
 * UI state holder shared by desktop and Android. It holds no transfer logic of its own:
 * every status, number, and progress value comes from the engine, the history store, or
 * discovery.
 */
class AppController(
    val node: TransferXNode,
    val platform: PlatformBridge,
    private val scope: CoroutineScope,
) {
    val engine get() = node.engine

    var screen by mutableStateOf(Screen.HOME)
        private set
    private val backStack = ArrayDeque<Screen>()

    /** Files chosen for sending, in selection order. */
    val selection = mutableStateListOf<FileSource>()
    var skippedOnPick by mutableStateOf<List<SkippedEntry>>(emptyList())
    var focusedSession by mutableStateOf<String?>(null)
    var showManualConnect by mutableStateOf(false)

    /** A transferx:// link received from outside (e.g. a QR code scanned with the camera app). */
    var pendingLink by mutableStateOf<String?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Live sessions merged over persisted history, newest first. */
    val transfers: StateFlow<List<SessionSnapshot>> = combine(engine.sessions, node.history.records) { live, hist ->
        val byId = LinkedHashMap<String, SessionSnapshot>()
        hist.forEach { byId[it.snapshot.id] = it.snapshot }
        live.values.forEach { byId[it.id] = it }
        byId.values.sortedByDescending { it.createdAt }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun navigate(to: Screen) {
        if (to == screen) return
        backStack.addLast(screen)
        if (backStack.size > 20) backStack.removeFirst()
        screen = to
    }

    /** Top-level destinations reset the back stack. */
    fun selectTab(to: Screen) {
        backStack.clear()
        screen = to
    }

    fun back(): Boolean {
        val prev = backStack.removeLastOrNull() ?: return false
        screen = prev
        return true
    }

    fun toast(text: String) {
        _messages.tryEmit(text)
    }

    // ---------------------------------------------------------------- selection

    fun addFiles() = scope.launch { addPicked(platform.pickFiles()) }

    fun addFolder() = scope.launch { addPicked(platform.pickFolderToSend()) }

    fun addPicked(result: PickResult) {
        val existing = selection.map { it.ref.location + "|" + it.relativeDir }.toHashSet()
        val fresh = result.sources.filter { existing.add(it.ref.location + "|" + it.relativeDir) }
        selection.addAll(fresh)
        skippedOnPick = result.skipped
        if (result.skipped.isNotEmpty()) toast("${result.skipped.size} item(s) skipped (links, special or unreadable files)")
        if (fresh.isNotEmpty() && screen != Screen.SEND) navigate(Screen.SEND)
    }

    fun removeFromSelection(source: FileSource) {
        selection.remove(source)
    }

    fun clearSelection() {
        selection.clear()
        skippedOnPick = emptyList()
    }

    // ---------------------------------------------------------------- sending

    fun isTrusted(device: DiscoveredDevice) = node.trust.byDeviceId(device.deviceId)?.fingerprint == device.fingerprint

    fun sendTo(device: DiscoveredDevice) {
        // The announced fingerprint is pinned: we only talk to the device whose announcement
        // was chosen. It's still untrusted until paired.
        start(ConnectTarget.Tcp(device.addresses, device.port, device.fingerprint, null, device.deviceId), device.name)
    }

    fun sendToLink(text: String, code: String?): Boolean {
        val link = ConnectionLink.parse(text, node.settingsStore.current.port) ?: return false
        val target = link.toTarget().let { t -> if (!code.isNullOrBlank()) t.copy(pairingCode = code) else t }
        start(target, link.name ?: link.hosts.first())
        return true
    }

    fun sendBluetooth(peer: BluetoothPeer) {
        val bt = platform.bluetooth ?: return
        start(bt.target(peer), peer.name)
    }

    private fun start(target: ConnectTarget, label: String) {
        if (selection.isEmpty()) {
            toast("Choose files to send first")
            navigate(Screen.SEND)
            return
        }
        val id = try {
            engine.send(target, selection.toList(), label)
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "Can't send these files"); return
        }
        clearSelection()
        openTransfer(id)
    }

    fun openTransfer(id: String) {
        focusedSession = id
        navigate(Screen.TRANSFER)
    }

    // ---------------------------------------------------------------- session actions

    fun pause(id: String) = engine.pause(id)
    fun resumePaused(id: String) = engine.resumePaused(id)
    fun cancel(id: String) = engine.cancel(id)
    fun cancelFile(id: String, fileId: String) = engine.cancelFile(id, fileId)

    fun resume(s: SessionSnapshot) {
        if (s.direction != Direction.SEND) {
            toast("Ask the sender to resume this transfer"); return
        }
        if (!engine.resume(s.id)) toast("This transfer can't be resumed: its files or the receiver are no longer available")
    }

    fun retry(s: SessionSnapshot) {
        val newId = engine.retry(s.id)
        if (newId == null) toast("Nothing left to retry, or the files are no longer available") else openTransfer(newId)
    }

    fun forget(s: SessionSnapshot) {
        if (s.state.live) return
        if (!s.state.terminal) engine.cancel(s.id) // interrupted: discard temporary data first
        node.history.remove(s.id)
    }

    fun retryStorage(id: String) = engine.retryStorage(id)

    fun moveBlockedTransfer(id: String, current: String) = scope.launch {
        val dest = platform.pickDestination(current) ?: return@launch
        engine.retryStorage(id, Paths.get(dest))
    }

    // ---------------------------------------------------------------- prompts

    fun respond(promptId: String, accept: Boolean, fileIds: Set<String>?, destination: String?, policy: ConflictPolicy) {
        engine.respondToOffer(promptId, OfferDecision(accept, fileIds, destination?.let { Paths.get(it) }, policy))
    }

    fun changeOfferDestination(promptId: String, current: String) = scope.launch {
        val dest = platform.pickDestination(current) ?: return@launch
        engine.recheckOffer(promptId, Paths.get(dest))
    }

    fun recheckOffer(promptId: String, destination: String) = engine.recheckOffer(promptId, Paths.get(destination))

    // ---------------------------------------------------------------- settings

    fun updateSettings(transform: (com.transferx.core.app.AppSettings) -> com.transferx.core.app.AppSettings) =
        node.settingsStore.update(transform)

    fun chooseDefaultDestination() = scope.launch {
        val dest = platform.pickDestination(node.settingsStore.current.destination) ?: return@launch
        node.settingsStore.update { it.copy(destination = dest) }
    }

    fun connectionLink(): String = node.connectionLink()
}
