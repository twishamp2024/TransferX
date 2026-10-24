package com.transferx.core.support

import com.transferx.core.persistence.HistoryStore
import com.transferx.core.persistence.ReceiveIndex
import com.transferx.core.security.DeviceIdentity
import com.transferx.core.security.TrustStore
import com.transferx.core.security.TrustedDevice
import com.transferx.core.storage.NioStorageBackend
import com.transferx.core.storage.PartialWriter
import com.transferx.core.storage.StorageBackend
import com.transferx.core.transfer.ConflictPolicy
import com.transferx.core.transfer.ConnectTarget
import com.transferx.core.transfer.EngineConfig
import com.transferx.core.transfer.FileSource
import com.transferx.core.transfer.LocalDevice
import com.transferx.core.transfer.LocalFileSource
import com.transferx.core.transfer.OfferDecision
import com.transferx.core.transfer.Prompt
import com.transferx.core.transfer.ReceiveSettings
import com.transferx.core.transfer.SessionSnapshot
import com.transferx.core.transfer.SourceRef
import com.transferx.core.transfer.TransferEngine
import com.transferx.core.util.Hashing
import com.transferx.core.util.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * One simulated TransferX installation: its own identity, data directory, download
 * directory, and engine, listening on a loopback port. Two nodes in one JVM talk over
 * real TCP + TLS exactly like two devices on a LAN.
 */
class TestNode(
    val root: Path,
    val name: String,
    val platform: String,
    val config: EngineConfig = testConfig(),
    val storage: StorageBackend = NioStorageBackend(),
    identity: DeviceIdentity? = null,
) : AutoCloseable {
    val dataDir: Path = Files.createDirectories(root.resolve("data"))
    val downloads: Path = Files.createDirectories(root.resolve("downloads"))
    val identity: DeviceIdentity = identity ?: DeviceIdentity.loadOrCreate(dataDir)
    val trust = TrustStore(dataDir.resolve("trusted.json"))
    val history = HistoryStore(dataDir.resolve("history.json"))
    val index = ReceiveIndex(dataDir.resolve("receive-index.json"))
    @Volatile var accepting = true
    @Volatile var conflictPolicy = ConflictPolicy.RENAME
    @Volatile var destination: Path = downloads
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine = TransferEngine(
        config, this.identity, trust, history, index, storage,
        localDevice = { LocalDevice(name, platform) },
        receiveSettings = { ReceiveSettings(destination, accepting, conflictPolicy) },
        scope = scope,
    )
    val port: Int = engine.startListening(0, InetAddress.getLoopbackAddress())
    private var auto: Job? = null

    fun target(code: String? = null, port: Int = this.port, pin: Boolean = true) =
        ConnectTarget.Tcp(listOf("127.0.0.1"), port, if (pin) identity.fingerprintHex else null, code, identity.deviceId)

    /** Responds to every incoming offer with [decide]. */
    fun autoRespond(decide: (Prompt.IncomingOffer) -> OfferDecision?) {
        auto?.cancel()
        auto = scope.launch {
            val handled = HashSet<String>()
            engine.prompts.collect { list ->
                for (p in list) if (p is Prompt.IncomingOffer && handled.add(p.id)) {
                    decide(p)?.let { engine.respondToOffer(p.id, it) }
                }
            }
        }
    }

    fun autoAccept(policy: ConflictPolicy = ConflictPolicy.RENAME) = autoRespond { OfferDecision(true, conflictPolicy = policy) }

    fun stopAuto() {
        auto?.cancel()
    }

    fun awaitSession(id: String, timeoutMs: Long = 60_000, predicate: (SessionSnapshot) -> Boolean): SessionSnapshot = runBlocking {
        try {
            withTimeout(timeoutMs) { engine.sessions.first { m -> m[id]?.let(predicate) == true }[id]!! }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val last = engine.sessions.value[id]
            throw AssertionError(
                "[$name] condition not reached in $timeoutMs ms; last: state=${last?.state} paused=${last?.pausedByPeer} " +
                    "error=${last?.error} files=${last?.files?.map { "${it.status}:${it.bytesDone}" }}",
            )
        }
    }

    fun awaitAnySession(timeoutMs: Long = 60_000, predicate: (SessionSnapshot) -> Boolean): SessionSnapshot = runBlocking {
        withTimeout(timeoutMs) { engine.sessions.first { m -> m.values.any(predicate) }.values.first(predicate) }
    }

    fun <T : Prompt> awaitPrompt(type: Class<T>, timeoutMs: Long = 30_000): T = runBlocking {
        withTimeout(timeoutMs) {
            @Suppress("UNCHECKED_CAST")
            engine.prompts.first { l -> l.any { type.isInstance(it) } }.first { type.isInstance(it) } as T
        }
    }

    override fun close() {
        auto?.cancel()
        engine.close()
        scope.cancel()
    }
}

fun testConfig(
    chunkSize: Int = 64 * 1024,
    maxReconnectAttempts: Int = 3,
    integrityRetries: Int = 1,
    approvalTimeoutMs: Long = 60_000,
    connectTimeoutMs: Int = 3_000,
) = EngineConfig(
    chunkSize = chunkSize,
    checkpointIntervalBytes = 256L * 1024,
    maxInFlightBytes = 1024L * 1024,
    storageReserveBytes = 64L * 1024,
    reconnectBackoffMs = listOf(100, 200, 300),
    maxReconnectAttempts = maxReconnectAttempts,
    integrityRetries = integrityRetries,
    approvalTimeoutMs = approvalTimeoutMs,
    connectTimeoutMs = connectTimeoutMs,
    pingIntervalMs = 1_000,
    idleTimeoutMs = 10_000,
)

/** Pre-pairs two nodes (as if they had completed code pairing earlier). */
fun pair(a: TestNode, b: TestNode) {
    val now = System.currentTimeMillis()
    a.trust.trust(TrustedDevice(b.identity.deviceId, b.name, b.platform, b.identity.fingerprintHex, now))
    b.trust.trust(TrustedDevice(a.identity.deviceId, a.name, a.platform, a.identity.fingerprintHex, now))
}

object TestFiles {
    fun random(dir: Path, name: String, size: Long, seed: Long = name.hashCode().toLong()): Path {
        val p = dir.resolve(name)
        Files.createDirectories(p.parent)
        val rnd = Random(seed)
        Files.newOutputStream(p).use { out ->
            val buf = ByteArray(64 * 1024)
            var left = size
            while (left > 0) {
                rnd.nextBytes(buf)
                val n = minOf(left, buf.size.toLong()).toInt()
                out.write(buf, 0, n)
                left -= n
            }
        }
        return p
    }

    fun sha256(p: Path): String {
        val md = Hashing.sha256()
        Files.newInputStream(p).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return Hex.encode(md.digest())
    }

    /** All regular files under [dir] except TransferX's temporary folder. */
    fun visibleFiles(dir: Path): List<Path> = Files.walk(dir).use { s ->
        s.filter { Files.isRegularFile(it) && !it.toString().contains(".transferx-partial") }.sorted().toList()
    }
}

/**
 * A source that, like an Android content:// URI, has no filesystem path: it can only be
 * opened as a stream (and skipped to an offset). Used for the simulated phone senders.
 */
class StreamOnlySource(private val backing: Path, override val relativeDir: String = "") : FileSource {
    override val ref = SourceRef("content", "content://test/${backing.fileName}", relativeDir, backing.fileName.toString())
    override val size: Long = Files.size(backing)
    override val mimeType: String? = LocalFileSource.guessMime(backing.fileName.toString())
    override val modifiedAt: Long? = null // many content providers don't report it
    override fun open(offset: Long): InputStream {
        val input = Files.newInputStream(backing)
        var skipped = 0L
        while (skipped < offset) {
            val n = input.skip(offset - skipped)
            if (n <= 0) throw IOException("cannot skip")
            skipped += n
        }
        return input
    }

    override fun currentStat(): Pair<Long, Long?>? = Files.size(backing) to null
}

/**
 * Storage backend with a byte quota and switchable faults, for deterministic
 * disk-full and permission tests. Usage is the real size of files under [root].
 */
class FaultyStorage(private val root: Path) : NioStorageBackend() {
    val quota = AtomicLong(Long.MAX_VALUE)
    val writable = AtomicBoolean(true)

    /** Artificial per-write delay so tests can act mid-transfer deterministically. */
    val writeDelayMs = AtomicLong(0)

    /** When > 0, the next write covering this .part offset flips one byte on disk (silent corruption). */
    val corruptPartAt = AtomicLong(-1)
    val corruptions = AtomicInteger(0)

    fun used(): Long = Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }

    override fun usableSpace(dir: Path): Long? {
        val q = quota.get()
        return if (q == Long.MAX_VALUE) super.usableSpace(dir) else (q - used()).coerceAtLeast(0)
    }

    override fun canWrite(dir: Path): Boolean = writable.get() && super.canWrite(dir)

    private fun checkWrite(extra: Long): Long {
        if (!writable.get()) throw java.nio.file.AccessDeniedException("Permission denied (simulated)")
        val q = quota.get()
        if (q == Long.MAX_VALUE) return Long.MAX_VALUE
        return q - used() - extra.coerceAtLeast(0)
    }

    override fun openWriter(path: Path): PartialWriter {
        if (!writable.get()) throw java.nio.file.AccessDeniedException(path.toString(), null, "Permission denied (simulated)")
        val inner = super.openWriter(path)
        return object : PartialWriter by inner {
            override fun write(position: Long, data: ByteArray, offset: Int, length: Int) {
                writeDelayMs.get().takeIf { it > 0 }?.let { Thread.sleep(it) }
                val growth = (position + length - inner.size()).coerceAtLeast(0)
                val room = checkWrite(0)
                if (growth > room) {
                    // Like a real disk: part of the write lands, then ENOSPC.
                    val fits = (room - (position - inner.size()).coerceAtLeast(0)).coerceIn(0, length.toLong()).toInt()
                    if (fits > 0) inner.write(position, data, offset, fits)
                    throw IOException("No space left on device (simulated)")
                }
                val c = corruptPartAt.get()
                if (c >= 0 && path.toString().endsWith(".part") && c >= position && c < position + length) {
                    corruptPartAt.set(-1)
                    corruptions.incrementAndGet()
                    val copy = data.copyOfRange(offset, offset + length)
                    val i = (c - position).toInt()
                    copy[i] = (copy[i].toInt() xor 0xff).toByte()
                    inner.write(position, copy, 0, length)
                    return
                }
                inner.write(position, data, offset, length)
            }
        }
    }

    override fun append(path: Path, data: ByteArray) {
        if (data.size > checkWrite(0)) throw IOException("No space left on device (simulated)")
        super.append(path, data)
    }

    override fun writeAtomically(path: Path, bytes: ByteArray) {
        if (bytes.size > checkWrite(0)) throw IOException("No space left on device (simulated)")
        super.writeAtomically(path, bytes)
    }

    override fun move(source: Path, target: Path, replace: Boolean) {
        if (!writable.get()) throw java.nio.file.AccessDeniedException("Permission denied (simulated)")
        super.move(source, target, replace)
    }
}

/**
 * TCP relay between a sender and a receiver that can cut every connection on demand,
 * or automatically after a byte budget. Simulates Wi-Fi drops and roaming.
 */
class FaultProxy(private val targetPort: Int) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    val forwarded = AtomicLong(0)

    /** Cut all connections once this many bytes have passed (client → server). -1 = never. */
    val dropAfterBytes = AtomicLong(-1)
    val drops = AtomicInteger(0)
    @Volatile var refuse = false

    init {
        thread(isDaemon = true, name = "fault-proxy") {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: IOException) { break }
                if (refuse) { client.close(); continue }
                val upstream = try { Socket(InetAddress.getLoopbackAddress(), targetPort) } catch (e: IOException) { client.close(); continue }
                sockets += client; sockets += upstream
                pump(client, upstream, countForDrop = true)
                pump(upstream, client, countForDrop = false)
            }
        }
    }

    private fun pump(from: Socket, to: Socket, countForDrop: Boolean) = thread(isDaemon = true) {
        val buf = ByteArray(32 * 1024)
        try {
            val input = from.getInputStream()
            val out = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (countForDrop) {
                    val total = forwarded.addAndGet(n.toLong())
                    val limit = dropAfterBytes.get()
                    if (limit in 0..total) {
                        dropAfterBytes.set(-1)
                        dropAll()
                        break
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    fun dropAll() {
        drops.incrementAndGet()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    override fun close() {
        server.close()
        dropAll()
    }
}

/** Samples heap usage while [block] runs; returns (result, peak used heap bytes). */
fun <T> measurePeakHeap(block: () -> T): Pair<T, Long> {
    val bean = java.lang.management.ManagementFactory.getMemoryMXBean()
    val peak = AtomicLong(0)
    val running = AtomicBoolean(true)
    val sampler = thread(isDaemon = true) {
        while (running.get()) {
            val used = bean.heapMemoryUsage.used
            peak.updateAndGet { maxOf(it, used) }
            Thread.sleep(20)
        }
    }
    try {
        return block() to peak.get()
    } finally {
        running.set(false)
        sampler.join()
    }
}

object Metrics {
    private val file: Path = Path.of("build", "test-metrics.txt")

    @Synchronized
    fun record(line: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, line + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
}
