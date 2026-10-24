package com.transferx.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transferx.core.discovery.DiscoveredDevice
import com.transferx.core.discovery.NetUtils
import com.transferx.core.transfer.Direction
import com.transferx.core.transfer.SessionSnapshot
import com.transferx.core.transfer.SessionState
import com.transferx.core.app.ConnectionLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ===================================================================== Home

@Composable
fun HomeScreen(c: AppController) {
    val settings by c.node.settingsStore.settings.collectAsState()
    val devices by c.node.discovery.devices.collectAsState()
    val transfers by c.transfers.collectAsState()
    val port by c.engine.listeningPort.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("TransferX", style = MaterialTheme.typography.headlineSmall)
            Text(
                "This device: ${settings.deviceName} · " + if (settings.receiving && port != null) "ready to receive" else "receiving off",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { c.addFiles() }, Modifier.weight(1f).heightIn(min = 64.dp)) { Text("Send files", fontSize = 18.sp) }
            FilledTonalButton(onClick = { c.navigate(Screen.RECEIVE) }, Modifier.weight(1f).heightIn(min = 64.dp)) { Text("Receive", fontSize = 18.sp) }
        }
        val active = transfers.filter { it.state.live }
        if (active.isNotEmpty()) {
            SectionCard("Active transfers") {
                active.forEach { TransferRow(it) { c.openTransfer(it.id) } }
            }
        }
        SectionCard("Nearby devices") {
            val available = devices.filter { it.available }
            if (available.isEmpty()) {
                Text("No devices found yet. Open TransferX on the other device and make sure both are on the same Wi-Fi.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                available.take(4).forEach { d -> DeviceRow(c, d, devices) }
            }
            TextButton(onClick = { c.navigate(Screen.DEVICES) }) { Text("All nearby devices and connection options") }
        }
        SectionCard("Recent transfers") {
            val recent = transfers.filter { !it.state.live }.take(5)
            if (recent.isEmpty()) Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            recent.forEach { TransferRow(it) { c.openTransfer(it.id) } }
            if (recent.isNotEmpty()) TextButton(onClick = { c.selectTab(Screen.HISTORY) }) { Text("Show all") }
        }
    }
}

@Composable
fun TransferRow(s: SessionSnapshot, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.direction == Direction.SEND) "To " else "From ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s.peer.name, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1)
            StatePill(s.state)
        }
        val files = if (s.files.size == 1) s.files[0].name else "${s.files.size} files"
        // Declined offers have every file skipped; show what was offered rather than "0 B".
        val total = if (s.state == SessionState.REJECTED) s.files.sumOf { it.size } else s.totalBytes
        Text(
            "$files · ${Format.bytes(total)}" + (Format.speed(s.bytesPerSecond)?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.state.live) BytesProgress(s.bytesDone, s.totalBytes, Modifier.padding(top = 4.dp))
    }
}

// ===================================================================== Send

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SendScreen(c: AppController) {
    val devices by c.node.discovery.devices.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Send", style = MaterialTheme.typography.headlineSmall)
        SectionCard("1 · Files") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.addFiles() }) { Text("Add files") }
                if (c.platform.supportsFolderSelection) OutlinedButton(onClick = { c.addFolder() }) { Text("Add folder") }
                if (c.selection.isNotEmpty()) TextButton(onClick = { c.clearSelection() }) { Text("Clear") }
            }
            if (c.selection.isEmpty()) {
                Text("Photos, videos, music, documents, archives, any file type. Large files are streamed, never loaded into memory.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("${c.selection.size} file(s) · ${Format.bytes(c.selection.sumOf { it.size })}", fontWeight = FontWeight.Medium)
                c.selection.take(200).forEach { f ->
                    FileLine(f.name, listOfNotNull(f.relativeDir.ifEmpty { null }, Format.bytes(f.size), f.mimeType).joinToString(" · ")) {
                        IconButton(onClick = { c.removeFromSelection(f) }) { Icon(Icons.Default.Close, contentDescription = "Remove ${f.name}") }
                    }
                }
                if (c.selection.size > 200) Text("…and ${c.selection.size - 200} more")
            }
            if (c.skippedOnPick.isNotEmpty()) {
                Text("Skipped: " + c.skippedOnPick.take(5).joinToString { "${it.path.substringAfterLast('/').substringAfterLast('\\')} (${it.reason})" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        SectionCard("2 · Send to") {
            DeviceTargets(c, devices, enabled = c.selection.isNotEmpty())
        }
    }
}

@Composable
private fun DeviceTargets(c: AppController, devices: List<DiscoveredDevice>, enabled: Boolean) {
    val status by c.node.discovery.status.collectAsState()
    val available = devices.filter { it.available }
    if (available.isEmpty()) {
        Text(
            if (status.suggestFallback) "No devices found. The network may block discovery (guest or isolated Wi-Fi)." else "Looking for nearby devices…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    available.forEach { d ->
        DeviceRow(c, d, devices, action = {
            Button(onClick = { c.sendTo(d) }, enabled = enabled) { Text("Send") }
        })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { c.showManualConnect = true }, enabled = enabled) { Text("Enter address or link") }
        TextButton(onClick = { c.node.discovery.refresh() }) { Text("Refresh") }
    }
    BluetoothTargets(c, enabled)
}

@Composable
fun BluetoothTargets(c: AppController, enabled: Boolean) {
    val bt = c.platform.bluetooth ?: return
    val state by bt.state.collectAsState()
    HorizontalDivider()
    Text("Bluetooth (slower; best for small files)", style = MaterialTheme.typography.labelLarge)
    when (state) {
        BluetoothState.UNSUPPORTED -> Text("This device has no Bluetooth adapter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        BluetoothState.NO_PERMISSION, BluetoothState.OFF -> {
            Text(if (state == BluetoothState.OFF) "Bluetooth is off." else "Bluetooth permission is needed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { bt.requestEnable() }) { Text(if (state == BluetoothState.OFF) "Turn on Bluetooth" else "Grant permission") }
        }
        else -> {
            val peers = remember(state) { bt.bondedDevices() }
            if (peers.isEmpty()) Text("No paired Bluetooth devices. Pair the phones in system Bluetooth settings first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            peers.forEach { p ->
                FileLine(p.name, "Bluetooth · paired in system settings") {
                    Button(onClick = { c.sendBluetooth(p) }, enabled = enabled) { Text("Send") }
                }
            }
        }
    }
}

@Composable
fun DeviceRow(c: AppController, d: DiscoveredDevice, all: List<DiscoveredDevice>, action: (@Composable () -> Unit)? = null) {
    val duplicate = all.count { it.name == d.name } > 1
    val label = if (duplicate) "${d.name} (${d.fingerprint.take(4).uppercase()})" else d.name
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, fontWeight = FontWeight.Medium)
                if (c.isTrusted(d)) Pill("Paired", MaterialTheme.colorScheme.primaryContainer)
                if (!d.available) Pill("Not responding", MaterialTheme.colorScheme.tertiaryContainer)
            }
            Text(
                "${Format.platform(d.platform)} · ${d.addresses.firstOrNull() ?: "?"} · " + d.transports.joinToString(" + ") { if (it == "wifi") "Wi-Fi" else if (it == "bluetooth") "Bluetooth" else it },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.invoke()
    }
}

// ===================================================================== Receive

@Composable
fun ReceiveScreen(c: AppController) {
    val settings by c.node.settingsStore.settings.collectAsState()
    val port by c.engine.listeningPort.collectAsState()
    val code by c.engine.pairing.current.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            val cur = c.engine.pairing.current.value
            if (cur == null || cur.expiresAt <= now) c.engine.pairing.activeCode() // keep a valid code on screen
            delay(1_000)
        }
    }
    // Interface enumeration takes 100+ ms on Windows machines with many adapters: never on the UI thread.
    val addresses by produceState(emptyList<String>()) {
        while (true) {
            value = withContext(Dispatchers.IO) { NetUtils.advertisedAddresses() }
            delay(10_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Receive", style = MaterialTheme.typography.headlineSmall)
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (settings.receiving) "Ready to receive" else "Receiving is off", style = MaterialTheme.typography.titleMedium)
                    Text("You'll be asked to accept every transfer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.receiving, onCheckedChange = { v -> c.updateSettings { it.copy(receiving = v) } })
            }
        }
        if (addresses.isEmpty() || port == null) {
            SectionCard(tone = Tone.WARNING) {
                Text("Not connected to a network", fontWeight = FontWeight.Medium)
                Text("Join a Wi-Fi network (or a phone's hotspot) so other devices can reach this one.")
            }
        } else {
            val link = remember(code?.code, addresses, port, settings.deviceName) {
                ConnectionLink.format(c.node.identity.deviceId, settings.deviceName, addresses, port!!, c.node.identity.fingerprintHex, code?.code)
            }
            SectionCard("Connect from another device") {
                // Side by side on wide windows; QR above the details on phones, where a
                // 200 dp code next to the text would leave the details too narrow to read.
                BoxWithConstraints {
                    val narrow = maxWidth < 480.dp
                    val qr = @Composable {
                        QrImage(link, Modifier.size(if (narrow) 240.dp else 200.dp), "QR code to connect to ${settings.deviceName}")
                    }
                    val details = @Composable { modifier: Modifier ->
                        ConnectDetails(c, modifier, addresses, port, code, now, link)
                    }
                    if (narrow) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            qr()
                            details(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                            qr()
                            details(Modifier.weight(1f))
                        }
                    }
                }
                Text(
                    "Devices you've already paired with don't need a code. Every device still needs your approval for each transfer.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionCard("This device") {
            LabeledValue("Name", settings.deviceName)
            LabeledValue("Fingerprint", c.node.identity.shortFingerprint, mono = true)
            LabeledValue("Saving to", settings.destination)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Visible to nearby devices")
                    Text("Turn off on public or untrusted Wi-Fi. You can still connect with the code.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.discoverable, onCheckedChange = { v -> c.updateSettings { it.copy(discoverable = v) } })
            }
        }
        c.platform.bluetooth?.let { bt ->
            val state by bt.state.collectAsState()
            SectionCard("Bluetooth") {
                Text(
                    when (state) {
                        BluetoothState.LISTENING -> "Listening for Bluetooth transfers from paired phones."
                        BluetoothState.READY -> "Bluetooth is available but not listening."
                        BluetoothState.OFF -> "Bluetooth is off."
                        BluetoothState.NO_PERMISSION -> "Bluetooth permission is needed."
                        BluetoothState.UNSUPPORTED -> "No Bluetooth adapter."
                    },
                )
                when (state) {
                    BluetoothState.READY -> Button(onClick = { bt.startListening() }) { Text("Listen on Bluetooth") }
                    BluetoothState.LISTENING -> OutlinedButton(onClick = { bt.stopListening() }) { Text("Stop listening") }
                    BluetoothState.OFF, BluetoothState.NO_PERMISSION -> OutlinedButton(onClick = { bt.requestEnable() }) { Text("Enable") }
                    else -> {}
                }
            }
        }
    }
}

// ===================================================================== Nearby devices

@Composable
fun DevicesScreen(c: AppController) {
    val devices by c.node.discovery.devices.collectAsState()
    val status by c.node.discovery.status.collectAsState()
    val trusted by c.node.trust.devices.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nearby devices", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { c.node.discovery.refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
        }
        status.problem?.let { p -> SectionCard(tone = Tone.WARNING) { Text(p) } }
        SectionCard {
            if (devices.isEmpty()) {
                Text(if (status.running) "Searching on the local network…" else "Discovery is not running.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            devices.forEach { d ->
                DeviceRow(c, d, devices, action = {
                    Button(onClick = { if (c.selection.isEmpty()) c.navigate(Screen.SEND) else c.sendTo(d) }, enabled = d.available) {
                        Text(if (c.selection.isEmpty()) "Choose files" else "Send ${c.selection.size}")
                    }
                })
            }
        }
        if (status.suggestFallback && devices.none { it.available }) {
            SectionCard("Can't find the other device?", tone = Tone.INFO) {
                Text("Some routers and guest networks block device discovery. You can still connect directly:")
                Text("1. On the receiving device, open Receive.\n2. Scan its QR code, or enter its address and pairing code here.")
                Button(onClick = { c.showManualConnect = true }) { Text("Connect with address or link") }
                Text(
                    "Different Wi-Fi networks can't reach each other. Join the same network, or connect both devices to one phone's hotspot.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        BluetoothTargets(c, enabled = c.selection.isNotEmpty())
        if (trusted.isNotEmpty()) {
            SectionCard("Paired devices") {
                trusted.forEach { t ->
                    LabeledValue(t.name, "${Format.platform(t.platform)} · ${t.fingerprint.take(16).chunked(4).joinToString(" ").uppercase()}", mono = false)
                }
            }
        }
    }
}

@Composable
fun ManualConnectDialog(c: AppController) {
    var address by rememberSaveable { mutableStateOf(c.pendingLink ?: "") }
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { c.showManualConnect = false },
        title = { Text("Connect directly") },
        text = {
            Column(Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the address shown on the receiver's Receive screen (for example 192.168.1.20:47820), or paste its transferx:// link.")
                OutlinedTextField(address, { address = it; error = null }, label = { Text("Address or link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it }, label = { Text("Pairing code (if not paired yet)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.size(1.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                if (c.selection.isEmpty()) {
                    error = "Choose files to send first."
                } else if (c.sendToLink(address, code.ifBlank { null })) {
                    c.showManualConnect = false
                    c.pendingLink = null
                } else {
                    error = "That isn't a valid address (IP:port) or TransferX link."
                }
            }) { Text("Connect and send") }
        },
        dismissButton = { TextButton(onClick = { c.showManualConnect = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ConnectDetails(
    c: AppController,
    modifier: Modifier,
    addresses: List<String>,
    port: Int?,
    code: com.transferx.core.security.PairingCodeManager.ActiveCode?,
    now: Long,
    link: String,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Scan with TransferX on the sending device, or enter:", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LabeledValue("Address", addresses.joinToString("\n") { "$it:$port" }, mono = true)
        code?.let { cd ->
            Text("Pairing code", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(cd.display, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            val left = ((cd.expiresAt - now) / 1000).coerceAtLeast(0)
            Text("Expires in ${Format.duration(left)} · single use", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { c.engine.pairing.rotate() }) { Text("New code") }
            TextButton(onClick = { c.platform.copyToClipboard(link); c.toast("Link copied") }) { Text("Copy link") }
        }
    }
}
