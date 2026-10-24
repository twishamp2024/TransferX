package com.transferx.cli

import com.transferx.core.app.ConnectionLink
import com.transferx.core.app.DesktopPaths
import com.transferx.core.app.TransferXNode
import com.transferx.core.discovery.NetUtils
import com.transferx.core.protocol.FileStatus
import com.transferx.core.transfer.ConflictPolicy
import com.transferx.core.transfer.ConnectTarget
import com.transferx.core.transfer.LocalSelection
import com.transferx.core.transfer.OfferDecision
import com.transferx.core.transfer.Prompt
import com.transferx.core.transfer.SessionSnapshot
import com.transferx.core.transfer.SessionState
import com.transferx.core.util.LogLevel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val USAGE = """TransferX command-line client

Usage: transferx-cli [--home DIR] [--name NAME] [--port N] [--verbose] <command> [options]

Commands:
  info                         Show this device's name, id, fingerprint and addresses
  receive [--dest DIR] [--yes] [--policy rename|skip|replace] [--exit-after N]
                               Wait for incoming transfers. Without --yes, each offer is confirmed on stdin.
  send --to HOST[:PORT]|LINK [--code CODE] PATH...
                               Send files/folders. LINK is a transferx://connect?... link from a QR code.
  discover [--seconds N]       List TransferX devices on the local network
  history [--limit N]          Show recent transfers
  resume SESSION_ID            Resume an interrupted outgoing transfer

Environment: TRANSFERX_HOME overrides the data directory (useful for running two instances on one machine).
"""

fun main(argv: Array<String>) {
    val args = argv.toMutableList()
    fun opt(name: String): String? {
        val i = args.indexOf(name)
        if (i < 0 || i + 1 >= args.size) return null
        val v = args[i + 1]
        args.removeAt(i); args.removeAt(i)
        return v
    }
    fun flag(name: String): Boolean = args.remove(name)

    val home = opt("--home")?.let { Paths.get(it) } ?: DesktopPaths.defaultDataDir()
    val name = opt("--name")
    val port = opt("--port")?.toIntOrNull()
    com.transferx.core.util.Log.minLevel = if (flag("--verbose")) LogLevel.DEBUG else LogLevel.WARN
    val command = args.removeFirstOrNull() ?: run { print(USAGE); exitProcess(64) }

    val node = TransferXNode(home, DesktopPaths.platform, DesktopPaths.defaultDeviceName(), DesktopPaths.defaultDownloads())
    if (name != null) node.settingsStore.update { it.copy(deviceName = name) }
    if (port != null) node.settingsStore.update { it.copy(port = port) }

    val code = try {
        when (command) {
            "info" -> info(node)
            "receive" -> receive(node, opt("--dest"), flag("--yes"), opt("--policy"), opt("--exit-after")?.toIntOrNull())
            "send" -> send(node, opt("--to") ?: fail("send needs --to"), opt("--code"), args.toList())
            "discover" -> discover(node, opt("--seconds")?.toLongOrNull() ?: 6)
            "history" -> history(node, opt("--limit")?.toIntOrNull() ?: 20)
            "resume" -> resume(node, args.firstOrNull() ?: fail("resume needs a session id"))
            "help", "--help", "-h" -> { print(USAGE); 0 }
            else -> fail("unknown command '$command'")
        }
    } finally {
        node.close()
    }
    exitProcess(code)
}

private fun fail(msg: String): Nothing {
    System.err.println("error: $msg")
    exitProcess(64)
}

private fun human(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble(); var u = 0
    while (v >= 1000 && u < units.lastIndex) { v /= 1000; u++ }
    return if (u == 0) "$bytes B" else "%.1f %s".format(v, units[u])
}

private fun info(node: TransferXNode): Int {
    val s = node.settingsStore.current
    println("Name:        ${s.deviceName}")
    println("Platform:    ${node.platform}")
    println("Device id:   ${node.identity.deviceId}")
    println("Fingerprint: ${node.identity.shortFingerprint}  (${node.identity.fingerprintHex})")
    println("Addresses:   ${NetUtils.advertisedAddresses().joinToString().ifEmpty { "none (no network)" }}")
    println("Port:        ${s.port}")
    println("Downloads:   ${s.destination}")
    println("Data dir:    ${node.dataDir}")
    return 0
}

/** Lines typed on stdin, read on a background thread so the coroutines never block on it. */
private val stdinLines = Channel<String>(Channel.UNLIMITED).also { ch ->
    thread(isDaemon = true, name = "stdin") {
        while (true) {
            val line = readLine() ?: break
            ch.trySend(line)
        }
        ch.close()
    }
}

private suspend fun ask(question: String): String {
    print(question); System.out.flush()
    return stdinLines.receiveCatching().getOrNull()?.trim() ?: ""
}

private fun describe(s: SessionSnapshot): String {
    val pct = if (s.totalBytes > 0) s.bytesDone * 100 / s.totalBytes else 100
    val speed = s.bytesPerSecond?.let { " ${human(it)}/s" } ?: ""
    val eta = s.etaSeconds?.let { " ETA ${it}s" } ?: ""
    return "[${s.id.take(6)}] ${s.state} $pct% ${human(s.bytesDone)}/${human(s.totalBytes)}$speed$eta"
}

private fun printResult(s: SessionSnapshot) {
    println("[${s.id.take(6)}] finished: ${s.state}${s.error?.let { " - ${it.userMessage}" } ?: ""}")
    for (f in s.files) {
        val where = f.savedAs?.let { " -> $it" } ?: ""
        val why = f.error?.let { " (${it.userMessage})" } ?: ""
        println("   ${f.status.name.padEnd(11)} ${f.relativeDir.let { if (it.isEmpty()) "" else "$it/" }}${f.name}  ${human(f.size)}$where$why")
    }
}

private fun receive(node: TransferXNode, dest: String?, yes: Boolean, policy: String?, exitAfter: Int?): Int = runBlocking {
    if (dest != null) node.settingsStore.update { it.copy(destination = Paths.get(dest).toAbsolutePath().toString()) }
    val conflict = when (policy) {
        null, "rename" -> ConflictPolicy.RENAME
        "skip" -> ConflictPolicy.SKIP
        "replace" -> ConflictPolicy.REPLACE
        else -> fail("policy must be rename, skip or replace")
    }
    node.settingsStore.update { it.copy(conflictPolicy = conflict, receiving = true) }
    node.start()
    val s = node.settingsStore.current
    val code = node.engine.pairing.activeCode()
    println("Receiving as '${s.deviceName}' on ${NetUtils.advertisedAddresses().joinToString()} port ${node.engine.listeningPort.value}")
    println("Fingerprint ${node.identity.shortFingerprint}")
    println("Pairing code ${code.display} (valid 5 min)")
    println("Connection link: ${node.connectionLink()}")
    println("Saving to ${s.destination}")

    var finished = 0
    val handled = HashSet<String>()
    val done = HashSet<String>()
    val promptJob = launch {
        node.engine.prompts.collect { list ->
            for (p in list) {
                if (!handled.add(p.id)) continue
                when (p) {
                    is Prompt.PairingRequested ->
                        println("\nPairing request from ${p.peer.name} (${p.peer.platform}). Enter this code on that device: ${p.code}")
                    is Prompt.IncomingOffer -> launch {
                        println("\nIncoming from ${p.peer.name} (${p.peer.platform}, ${p.peer.address}): ${p.files.size} file(s), ${human(p.totalBytes)}")
                        p.files.forEach { f ->
                            val notes = listOfNotNull(
                                "exists".takeIf { f.conflictsWithExisting }, "won't fit".takeIf { !f.fits },
                                "too large for filesystem".takeIf { f.tooLargeForFilesystem },
                            ).joinToString()
                            println("   ${f.relativeDir.let { if (it.isEmpty()) "" else "$it/" }}${f.name}  ${human(f.size)}${if (notes.isNotEmpty()) "  [$notes]" else ""}")
                        }
                        val pre = p.preflight
                        val avail = pre.availableBytes?.let { human(it) } ?: "unknown"
                        println("   Space: need ${human(pre.requiredBytes)}, available $avail")
                        if (!pre.sufficient) println("   WARNING: ${pre.nonFittingFileIds.size} file(s) don't fit; only files that fit will be accepted.")
                        val accept = yes || ask("Accept? [y/N] ").lowercase().startsWith("y")
                        node.engine.respondToOffer(p.id, OfferDecision(accept, conflictPolicy = conflict))
                    }
                    else -> {}
                }
            }
        }
    }
    var lastPrint = 0L
    node.engine.sessions.first { map ->
        val now = System.currentTimeMillis()
        for (snap in map.values) {
            if (snap.id in done) continue
            if (snap.state.terminal || snap.state == SessionState.INTERRUPTED) {
                done += snap.id; finished++; printResult(snap)
            }
        }
        if (now - lastPrint > 1000) {
            lastPrint = now
            map.values.filter { it.state.live && it.id !in done }.forEach { println(describe(it)) }
        }
        exitAfter != null && finished >= exitAfter
    }
    promptJob.cancel() // runBlocking would otherwise wait for the collector forever
    0
}

private fun send(node: TransferXNode, to: String, code: String?, paths: List<String>): Int = runBlocking {
    if (paths.isEmpty()) fail("nothing to send")
    val link = ConnectionLink.parse(to, node.settingsStore.current.port) ?: fail("'$to' is not an address or TransferX link")
    val selection = LocalSelection.expand(paths.map { Paths.get(it).toAbsolutePath() })
    selection.skipped.forEach { println("skipped ${it.path}: ${it.reason}") }
    if (selection.sources.isEmpty()) fail("no readable files")
    node.start(listen = false)
    val target: ConnectTarget = link.toTarget().let { if (code != null) it.copy(pairingCode = code) else it }
    val id = node.engine.send(target, selection.sources, link.name)
    println("Sending ${selection.sources.size} file(s), ${human(selection.sources.sumOf { it.size })} to ${link.name ?: link.hosts.first()}")
    val handled = HashSet<String>()
    val prompts = launch {
        node.engine.prompts.collect { list ->
            for (p in list) if (p is Prompt.PairingCodeNeeded && handled.add(p.id)) launch {
                if (p.previousAttemptFailed) println("Wrong code. ${p.attemptsRemaining} attempt(s) left.")
                val typed = ask("Enter the pairing code shown on ${p.peer.name}: ")
                if (!node.engine.submitPairingCode(p.id, typed)) {
                    println("That isn't a valid code format (12 letters/digits).")
                    node.engine.submitPairingCode(p.id, null)
                }
            }
        }
    }
    var lastPrint = 0L
    var lastState: SessionState? = null
    val final = node.engine.sessions.first { map ->
        val s = map[id] ?: return@first false
        val now = System.currentTimeMillis()
        if (s.state != lastState) {
            lastState = s.state
            when (s.state) {
                SessionState.PAIRING -> println("Pairing with ${s.peer.name}...")
                SessionState.AWAITING_APPROVAL -> println("Waiting for ${s.peer.name} to accept...")
                SessionState.BLOCKED -> println("Receiver blocked: ${s.storageIssue?.reason?.userMessage}")
                else -> {}
            }
        }
        if (now - lastPrint > 1000 && s.state.live) {
            lastPrint = now; println(describe(s))
        }
        // INTERRUPTED is only published once automatic reconnects are exhausted (retries show CONNECTING).
        s.state.terminal || s.state == SessionState.INTERRUPTED
    }[id]!!
    prompts.cancel()
    printResult(final)
    when {
        final.state == SessionState.COMPLETED -> 0
        final.files.any { it.status == FileStatus.COMPLETED } -> 2
        else -> 1
    }
}

private fun resume(node: TransferXNode, id: String): Int = runBlocking {
    node.start(listen = false)
    val full = node.history.records.value.firstOrNull { it.snapshot.id.startsWith(id) }?.snapshot?.id ?: fail("no session $id")
    if (!node.engine.resume(full)) fail("session $full can't be resumed")
    val final = withTimeoutOrNull(24 * 3600 * 1000L) {
        node.engine.sessions.first { m -> m[full]?.state?.terminal == true || m[full]?.state == SessionState.INTERRUPTED }[full]
    }
    final?.let { printResult(it) }
    if (final?.state == SessionState.COMPLETED) 0 else 1
}

private fun discover(node: TransferXNode, seconds: Long): Int = runBlocking {
    node.start()
    println("Searching for ${seconds}s...")
    delay(seconds * 1000)
    val devices = node.discovery.devices.value
    if (devices.isEmpty()) {
        println("No TransferX devices found.")
        println("If both devices are on the same Wi-Fi, the network may block discovery (guest/isolated Wi-Fi).")
        println("Use the address and pairing code shown on the receiver: transferx-cli send --to <address> PATH")
    }
    val dupes = devices.groupBy { it.name }.filterValues { it.size > 1 }.keys
    for (d in devices) {
        val label = if (d.name in dupes) "${d.name} (${d.fingerprint.take(4)})" else d.name
        println("${if (d.available) "*" else "-"} $label  ${d.platform}  ${d.addresses.joinToString()}:${d.port}  [${d.transports.joinToString()}]")
    }
    node.discovery.status.value.problem?.let { println("Note: $it") }
    0
}

private fun history(node: TransferXNode, limit: Int): Int {
    val recs = node.history.records.value.take(limit)
    if (recs.isEmpty()) println("No transfers yet.")
    for (r in recs) {
        val s = r.snapshot
        val dir = if (s.direction.name == "SEND") "->" else "<-"
        println("${s.id.take(8)} $dir ${s.peer.name.padEnd(20)} ${s.state.name.padEnd(12)} ${s.completedCount}/${s.files.size} files ${human(s.totalBytes)}" +
            (if (s.resumable) "  (resumable)" else "") + (s.error?.let { "  ${it.name}" } ?: ""))
    }
    return 0
}
