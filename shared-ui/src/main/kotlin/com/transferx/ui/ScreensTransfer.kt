package com.transferx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.transferx.core.protocol.ErrorCode
import com.transferx.core.protocol.FileStatus
import com.transferx.core.transfer.Direction
import com.transferx.core.transfer.SessionSnapshot
import com.transferx.core.transfer.SessionState
import com.transferx.core.transfer.TransportKind

// ===================================================================== Transfer progress

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferScreen(c: AppController) {
    val transfers by c.transfers.collectAsState()
    val s = transfers.firstOrNull { it.id == c.focusedSession }
    if (s == null) {
        EmptyState("No transfer selected", "Start one from Send, or open one from History.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (s.direction == Direction.SEND) "Sending to " else "Receiving from ") + s.peer.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${Format.platform(s.peer.platform)} · " + (if (s.transport == TransportKind.BLUETOOTH) "Bluetooth" else "Wi-Fi") +
                        (s.peer.address?.let { " · $it" } ?: "") + " · started ${Format.time(s.createdAt)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatePill(s.state)
        }

        SectionCard {
            val done = s.files.count { it.status == FileStatus.COMPLETED }
            val counted = s.files.count { it.status != FileStatus.SKIPPED }
            Text("${Format.bytes(s.bytesDone)} of ${Format.bytes(s.totalBytes)} · $done of $counted file(s) done", fontWeight = FontWeight.Medium)
            BytesProgress(s.bytesDone, s.totalBytes)
            val live = listOfNotNull(Format.speed(s.bytesPerSecond), Format.duration(s.etaSeconds)?.let { "$it left" })
            Text(
                when {
                    s.state == SessionState.CONNECTING && s.reconnectAttempt > 0 -> "Connection lost. Reconnecting (attempt ${s.reconnectAttempt})…"
                    s.state == SessionState.CONNECTING -> "Connecting…"
                    s.state == SessionState.PAIRING -> "Pairing: enter the code shown on ${s.peer.name}."
                    s.state == SessionState.AWAITING_APPROVAL -> "Waiting for ${s.peer.name} to accept…"
                    s.state == SessionState.PAUSED -> if (s.pausedByPeer) "Paused by ${s.peer.name}" else "Paused"
                    live.isNotEmpty() -> live.joinToString(" · ")
                    s.state == SessionState.TRANSFERRING -> "Measuring speed…"
                    else -> Format.state(s.state)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionActions(c, s)
            }
        }

        s.storageIssue?.takeIf { s.state == SessionState.BLOCKED }?.let { issue -> StorageBlockedCard(c, s, issue) }
        if (s.state != SessionState.BLOCKED) s.error?.let { e -> ErrorCard(e, s.errorDetail, s.state) }

        SectionCard("Files") {
            s.files.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider()
                Column(Modifier.padding(vertical = 4.dp)) {
                    FileLine(
                        f.name,
                        listOfNotNull(f.relativeDir.ifEmpty { null }, Format.bytes(f.size), f.savedAs?.let { "saved as $it" }).joinToString(" · "),
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            FileStatusText(f.status)
                            if (s.state.live && !f.status.terminal) {
                                TextButton(onClick = { c.cancelFile(s.id, f.fileId) }) { Text("Cancel file") }
                            }
                        }
                    }
                    if (f.status == FileStatus.TRANSFERRING || f.status == FileStatus.VERIFYING || f.status == FileStatus.BLOCKED ||
                        (f.status == FileStatus.INTERRUPTED && f.bytesDone > 0)
                    ) {
                        BytesProgress(f.bytesDone, f.size)
                    }
                    f.error?.takeIf { f.status != FileStatus.COMPLETED }?.let {
                        Text(it.userMessage, style = MaterialTheme.typography.bodySmall, color = if (it.category == ErrorCode.Category.SKIPPED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionActions(c: AppController, s: SessionSnapshot) {
    when {
        s.state == SessionState.TRANSFERRING || s.state == SessionState.BLOCKED -> {
            OutlinedButton(onClick = { c.pause(s.id) }, enabled = s.state == SessionState.TRANSFERRING) { Text("Pause") }
            OutlinedButton(onClick = { c.cancel(s.id) }) { Text("Cancel transfer") }
        }
        s.state == SessionState.PAUSED -> {
            Button(onClick = { c.resumePaused(s.id) }, enabled = !s.pausedByPeer) { Text("Resume") }
            OutlinedButton(onClick = { c.cancel(s.id) }) { Text("Cancel transfer") }
        }
        s.state.live -> OutlinedButton(onClick = { c.cancel(s.id) }) { Text("Cancel") }
        s.state == SessionState.INTERRUPTED -> {
            if (s.direction == Direction.SEND) Button(onClick = { c.resume(s) }) { Text("Resume") }
            else Text("Waiting for ${s.peer.name} to resume. Partial data is kept.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { c.forget(s) }) { Text("Discard") }
        }
        else -> {
            if (s.direction == Direction.SEND && s.retryable) Button(onClick = { c.retry(s) }) { Text("Retry unfinished files") }
            if (s.direction == Direction.RECEIVE && s.destination != null && s.completedCount > 0) {
                OutlinedButton(onClick = { c.platform.openFolder(s.destination!!) }) { Text("Open folder") }
            }
        }
    }
}

@Composable
private fun StorageBlockedCard(c: AppController, s: SessionSnapshot, issue: com.transferx.core.transfer.StorageIssue) {
    val permission = issue.reason.category == ErrorCode.Category.PERMISSION
    SectionCard(if (permission) "Can't write to the destination" else "Not enough space", tone = Tone.WARNING) {
        Text(issue.reason.userMessage)
        val file = s.files.firstOrNull { it.fileId == issue.fileId }
        file?.let { LabeledValue("Affected file", it.name) }
        if (!permission) {
            LabeledValue("Still needed", Format.bytes(issue.requiredBytes))
            LabeledValue("Available", Format.bytes(issue.availableBytes))
        }
        Text(
            "Files already received are safe. Verified data for this file is kept, and the transfer continues from there.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (s.direction == Direction.RECEIVE) {
            FlowRowActions {
                Button(onClick = { c.retryStorage(s.id) }) { Text(if (permission) "Check again" else "I freed space, continue") }
                OutlinedButton(onClick = { c.moveBlockedTransfer(s.id, issue.destination) }) { Text("Choose another folder") }
                TextButton(onClick = { c.cancelFile(s.id, issue.fileId ?: return@TextButton) }) { Text("Skip this file") }
            }
        } else {
            Text("Waiting for ${s.peer.name} to free space or pick another folder. The connection stays open.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowActions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun ErrorCard(e: ErrorCode, detail: String?, state: SessionState) {
    var expanded by remember { mutableStateOf(false) }
    val tone = if (e.category == ErrorCode.Category.CANCELLED || e.category == ErrorCode.Category.REJECTED) Tone.NORMAL else Tone.ERROR
    SectionCard(tone = tone) {
        Text(e.userMessage, fontWeight = FontWeight.Medium)
        if (state != SessionState.CANCELLED) Format.advice(e)?.let { Text(it) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
        if (expanded) {
            // Safe to show: an error code and category, never file contents, keys or pairing codes.
            LabeledValue("Error code", e.name, mono = true)
            LabeledValue("Category", e.category.name.lowercase())
            detail?.let { LabeledValue("Detail", it) }
        }
    }
}

// ===================================================================== History

private enum class HistoryFilter(val label: String, val test: (SessionSnapshot) -> Boolean) {
    ALL("All", { true }),
    COMPLETED("Completed", { it.state == SessionState.COMPLETED }),
    FAILED("Failed", { it.state == SessionState.FAILED || it.state == SessionState.PARTIAL || it.state == SessionState.REJECTED }),
    INTERRUPTED("Interrupted", { it.state == SessionState.INTERRUPTED }),
    CANCELLED("Cancelled", { it.state == SessionState.CANCELLED }),
    RESUMABLE("Resumable", { it.resumable }),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(c: AppController) {
    val transfers by c.transfers.collectAsState()
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Transfer history", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (transfers.any { it.state.terminal }) TextButton(onClick = { c.node.history.clearFinished() }) { Text("Clear finished") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryFilter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} (${transfers.count(f.test)})") })
            }
        }
        val shown = transfers.filter(filter.test)
        if (shown.isEmpty()) EmptyState("Nothing here", "Transfers you send or receive appear here, including failed and interrupted ones.")
        shown.forEach { s ->
            SectionCard {
                TransferRow(s) { c.openTransfer(s.id) }
                Text(Format.time(s.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.error?.let { Text(it.userMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { c.openTransfer(s.id) }) { Text("Details") }
                    if (s.direction == Direction.SEND && s.resumable && !s.state.live) TextButton(onClick = { c.resume(s) }) { Text("Resume") }
                    if (s.direction == Direction.SEND && s.retryable) TextButton(onClick = { c.retry(s) }) { Text("Retry") }
                    if (!s.state.live) TextButton(onClick = { c.forget(s) }) { Text(if (s.state == SessionState.INTERRUPTED) "Discard" else "Remove") }
                    Spacer(Modifier.width(1.dp))
                }
            }
        }
    }
}
