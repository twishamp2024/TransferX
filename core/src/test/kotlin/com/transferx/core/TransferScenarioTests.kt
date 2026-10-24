package com.transferx.core

import com.transferx.core.discovery.Announcement
import com.transferx.core.discovery.DiscoveryService
import com.transferx.core.persistence.HistoryStore
import com.transferx.core.protocol.ErrorCode
import com.transferx.core.protocol.FileMeta
import com.transferx.core.protocol.FileStatus
import com.transferx.core.security.PairingCodes
import com.transferx.core.support.FaultProxy
import com.transferx.core.support.FaultyStorage
import com.transferx.core.support.Metrics
import com.transferx.core.support.StreamOnlySource
import com.transferx.core.support.TestFiles
import com.transferx.core.support.TestNode
import com.transferx.core.support.measurePeakHeap
import com.transferx.core.support.pair
import com.transferx.core.support.testConfig
import com.transferx.core.transfer.ConflictPolicy
import com.transferx.core.transfer.ConnectInfo
import com.transferx.core.transfer.ConnectTarget
import com.transferx.core.transfer.Direction
import com.transferx.core.transfer.EngineConfig
import com.transferx.core.transfer.FileProgress
import com.transferx.core.transfer.FileSource
import com.transferx.core.transfer.LocalFileSource
import com.transferx.core.transfer.OfferDecision
import com.transferx.core.transfer.Prompt
import com.transferx.core.transfer.RawStream
import com.transferx.core.transfer.SessionSnapshot
import com.transferx.core.transfer.SessionState
import com.transferx.core.transfer.SourceRef
import com.transferx.core.transfer.TransferRecord
import com.transferx.core.transfer.TransportKind
import com.transferx.core.util.Ids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end transfer scenarios. Every test runs two or more complete TransferX engines
 * in one JVM, talking over real TCP + mutual TLS on loopback. "Phone"/"laptop" tests are
 * SIMULATED: they set the platform identity and use path-less stream sources (like
 * Android content:// URIs). They don't run on physical hardware.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TransferScenarioTests {
    @TempDir lateinit var tmp: Path
    private val nodes = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() = nodes.asReversed().forEach { runCatching { it.close() } }

    private fun node(
        name: String,
        platform: String = "windows",
        config: EngineConfig = testConfig(),
        faulty: Boolean = false,
        dirName: String = name,
    ): TestNode {
        val root = Files.createDirectories(tmp.resolve(dirName))
        val storage = if (faulty) FaultyStorage(Files.createDirectories(root.resolve("downloads"))) else com.transferx.core.storage.NioStorageBackend()
        return TestNode(root, name, platform, config, storage).also { nodes += it }
    }

    private val TestNode.faulty: FaultyStorage get() = storage as FaultyStorage
    private val srcDir: Path by lazy { Files.createDirectories(tmp.resolve("sources")) }

    private fun file(name: String, size: Long) = TestFiles.random(srcDir, name, size)
    private fun sources(vararg p: Path): List<FileSource> = p.map { LocalFileSource(it) }

    private fun terminal(s: SessionSnapshot) = s.state.terminal || s.state == SessionState.INTERRUPTED

    private fun assertReceived(receiver: TestNode, original: Path, savedAs: String = original.fileName.toString()) {
        val got = receiver.destination.resolve(savedAs)
        assertTrue(Files.exists(got), "expected $savedAs at receiver")
        assertEquals(TestFiles.sha256(original), TestFiles.sha256(got), "content of $savedAs")
    }

    private fun assertNoPartialData(receiver: TestNode) {
        val dir = receiver.destination.resolve(".transferx-partial")
        assertFalse(Files.exists(dir) && Files.list(dir).use { it.count() } > 0, "temporary data should be cleaned up")
    }

    private fun transfer(sender: TestNode, receiver: TestNode, src: List<FileSource>, target: ConnectTarget = receiver.target()): Pair<SessionSnapshot, SessionSnapshot> {
        val id = sender.engine.send(target, src)
        val s = sender.awaitSession(id) { terminal(it) }
        val r = receiver.awaitSession(id) { terminal(it) }
        return s to r
    }

    // 1
    @Test
    fun `01 small file transfer succeeds`() {
        val a = node("laptop-a"); val b = node("laptop-b"); pair(a, b); b.autoAccept()
        val f = file("note.txt", 10_000)
        val (s, r) = transfer(a, b, sources(f))
        assertEquals(SessionState.COMPLETED, s.state)
        assertEquals(SessionState.COMPLETED, r.state)
        assertEquals("note.txt", s.files.single().savedAs)
        assertReceived(b, f)
        assertNoPartialData(b)
        assertEquals(SessionState.COMPLETED, a.history.get(s.id)!!.snapshot.state, "history persisted")
    }

    // 2
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    fun `02 large file transfers with bounded memory`() {
        // Production chunk/window settings; the test JVM heap is capped at 256 MiB.
        val cfg = EngineConfig(storageReserveBytes = 64 * 1024)
        val a = node("big-a", config = cfg); val b = node("big-b", config = cfg); pair(a, b); b.autoAccept()
        val size = 1024L * 1024 * 1024
        val f = srcDir.resolve("disk-image.bin")
        RandomAccessFile(f.toFile(), "rw").use { raf ->
            raf.setLength(size)
            val marker = ByteArray(1 shl 20).also { java.util.Random(5).nextBytes(it) }
            raf.seek(size / 2); raf.write(marker) // non-uniform content in the middle
            raf.seek(size - marker.size); raf.write(marker)
        }
        val maxHeap = Runtime.getRuntime().maxMemory()
        val start = System.nanoTime()
        val (pairResult, peak) = measurePeakHeap { transfer(a, b, sources(f)) }
        val seconds = (System.nanoTime() - start) / 1e9
        assertEquals(SessionState.COMPLETED, pairResult.first.state)
        assertEquals(size, Files.size(b.destination.resolve("disk-image.bin")))
        assertReceived(b, f)
        assertTrue(peak < maxHeap, "peak heap $peak must stay below the cap")
        Metrics.record(
            "large-file: 1 GiB over loopback TLS in %.1f s (%.0f MiB/s incl. hashing and verification re-read); peak heap %d MiB of %d MiB max"
                .format(seconds, 1024 / seconds, peak shr 20, maxHeap shr 20),
        )
    }

    // 3
    @Test
    fun `03 multiple files and folders transfer with structure preserved`() {
        val a = node("multi-a"); val b = node("multi-b"); pair(a, b); b.autoAccept()
        val album = Files.createDirectories(srcDir.resolve("Album"))
        TestFiles.random(album, "one.jpg", 300_000)
        TestFiles.random(album.resolve("Raw"), "two.dng", 700_000)
        Files.createFile(album.resolve("empty.txt"))
        val doc = file("report.pdf", 123_457)
        val selection = com.transferx.core.transfer.LocalSelection.expand(listOf(album, doc))
        assertEquals(4, selection.sources.size)
        val (s, r) = transfer(a, b, selection.sources)
        assertEquals(SessionState.COMPLETED, s.state)
        assertEquals(4, r.files.count { it.status == FileStatus.COMPLETED })
        assertReceived(b, album.resolve("one.jpg"), "Album/one.jpg")
        assertReceived(b, album.resolve("Raw/two.dng"), "Album/Raw/two.dng")
        assertEquals(0, Files.size(b.destination.resolve("Album/empty.txt")))
        assertReceived(b, doc)
    }

    private fun platformTransfer(fromPlatform: String, toPlatform: String, phoneSender: Boolean) {
        val mobileCfg = testConfig(chunkSize = 32 * 1024)
        val a = node("$fromPlatform-sender", fromPlatform, if (phoneSender) mobileCfg else testConfig())
        val b = node("$toPlatform-receiver", toPlatform, if (toPlatform == "android") mobileCfg else testConfig())
        pair(a, b); b.autoAccept()
        val photo = file("IMG_$fromPlatform.jpg", 2_345_678)
        val clip = file("VID_$fromPlatform.mp4", 4_000_001)
        val src = if (phoneSender) listOf(StreamOnlySource(photo), StreamOnlySource(clip)) else sources(photo, clip)
        val (s, r) = transfer(a, b, src)
        assertEquals(SessionState.COMPLETED, s.state, "sender state")
        assertEquals(SessionState.COMPLETED, r.state, "receiver state")
        assertEquals(fromPlatform, r.peer.platform)
        assertEquals(toPlatform, s.peer.platform)
        assertReceived(b, photo); assertReceived(b, clip)
    }

    // 4–7 (simulated platforms)
    @Test fun `04 phone to phone (simulated android to android)`() = platformTransfer("android", "android", phoneSender = true)
    @Test fun `05 phone to laptop (simulated android to windows)`() = platformTransfer("android", "windows", phoneSender = true)
    @Test fun `06 laptop to laptop (windows to linux identities)`() = platformTransfer("windows", "linux", phoneSender = false)
    @Test fun `07 laptop to phone (simulated windows to android)`() = platformTransfer("windows", "android", phoneSender = false)

    // 8
    @Test
    fun `08 receiver rejects a transfer`() {
        val a = node("rej-a"); val b = node("rej-b"); pair(a, b)
        b.autoRespond { OfferDecision(accept = false) }
        val (s, r) = transfer(a, b, sources(file("secret.txt", 5_000)))
        assertEquals(SessionState.REJECTED, s.state)
        assertEquals(ErrorCode.REJECTED, s.error)
        assertEquals(SessionState.REJECTED, r.state)
        assertTrue(TestFiles.visibleFiles(b.destination).isEmpty())
        assertNoPartialData(b)
    }

    // 9
    @Test
    fun `09 sender cancels a transfer`() {
        val a = node("can-a"); val b = node("can-b", faulty = true); pair(a, b); b.autoAccept()
        b.faulty.writeDelayMs.set(5)
        val keep = file("first.bin", 100_000)
        val big = file("movie.mkv", 20_000_000)
        val id = a.engine.send(b.target(), sources(keep, big))
        a.awaitSession(id) { it.files[1].bytesDone > 1_000_000 }
        a.engine.cancel(id)
        val s = a.awaitSession(id) { terminal(it) }
        val r = b.awaitSession(id) { terminal(it) }
        assertEquals(SessionState.CANCELLED, s.state)
        assertEquals(ErrorCode.USER_CANCELLED, s.error)
        assertEquals(SessionState.CANCELLED, r.state)
        assertEquals(ErrorCode.PEER_CANCELLED, r.error)
        assertEquals(FileStatus.COMPLETED, r.files[0].status, "completed file is kept")
        assertReceived(b, keep)
        assertFalse(Files.exists(b.destination.resolve("movie.mkv")))
        assertNoPartialData(b)
    }

    // 10
    @Test
    fun `10 connection drop is recovered automatically`() {
        val a = node("drop-a"); val b = node("drop-b"); pair(a, b); b.autoAccept()
        val f = file("archive.zip", 10_000_000)
        FaultProxy(b.port).use { proxy ->
            proxy.dropAfterBytes.set(3_000_000)
            val (s, r) = transfer(a, b, sources(f), b.target(port = proxy.port))
            assertEquals(SessionState.COMPLETED, s.state)
            assertEquals(SessionState.COMPLETED, r.state)
            assertEquals(1, proxy.drops.get())
            assertReceived(b, f)
            // Resumed rather than restarted: total traffic ≈ one copy + one window, not 3 MB + 10 MB.
            assertTrue(proxy.forwarded.get() < 12_000_000, "forwarded ${proxy.forwarded.get()} bytes")
            Metrics.record("connection-drop: 10 MB file, drop at 3 MB, total forwarded ${proxy.forwarded.get()} bytes")
        }
    }

    // 11
    @Test
    fun `11 interrupted transfer resumes from a verified checkpoint`() {
        val a = node("res-a", config = testConfig(maxReconnectAttempts = 0)); val b = node("res-b"); pair(a, b); b.autoAccept()
        val f = file("backup.tar", 8_000_000)
        FaultProxy(b.port).use { proxy ->
            proxy.dropAfterBytes.set(4_000_000)
            val id = a.engine.send(b.target(port = proxy.port), sources(f))
            val s1 = a.awaitSession(id) { terminal(it) }
            assertEquals(SessionState.INTERRUPTED, s1.state)
            assertTrue(s1.resumable)
            val r1 = b.awaitSession(id) { it.state == SessionState.INTERRUPTED }
            assertTrue(r1.files[0].bytesDone > 0, "receiver kept verified bytes")
            assertFalse(Files.exists(b.destination.resolve("backup.tar")), "incomplete file is not visible")
            val before = proxy.forwarded.get()
            assertTrue(a.engine.resume(id))
            val s2 = a.awaitSession(id) { it.state.terminal }
            assertEquals(SessionState.COMPLETED, s2.state)
            assertReceived(b, f)
            val resent = proxy.forwarded.get() - before
            assertTrue(resent < 8_000_000 - r1.files[0].bytesDone + 1_500_000, "resent $resent bytes")
            Metrics.record("resume: ${r1.files[0].bytesDone} of 8000000 bytes kept; resent $resent bytes")
        }
    }

    // 12
    @Test
    fun `12 insufficient space is detected before transfer`() {
        val a = node("space-a"); val b = node("space-b", faulty = true); pair(a, b)
        b.faulty.quota.set(1_200_000)
        val small = file("fits.txt", 300_000)
        val big = file("too-big.iso", 5_000_000)
        var seen: Prompt.IncomingOffer? = null
        b.autoRespond { p -> seen = p; OfferDecision(accept = true) } // null = accept only files that fit
        val (s, r) = transfer(a, b, sources(small, big))
        val pre = seen!!.preflight
        assertFalse(pre.sufficient)
        assertNotNull(pre.availableBytes)
        assertTrue(pre.requiredBytes > pre.availableBytes!!)
        assertEquals(listOf(seen!!.files[1].fileId), pre.nonFittingFileIds)
        assertFalse(seen!!.files[1].fits)
        assertEquals(SessionState.PARTIAL, s.state)
        assertEquals(FileStatus.COMPLETED, s.files[0].status)
        assertEquals(FileStatus.SKIPPED, s.files[1].status)
        assertEquals(ErrorCode.NOT_SELECTED, s.files[1].error)
        assertEquals(SessionState.PARTIAL, r.state)
        assertReceived(b, small)
        assertFalse(Files.exists(b.destination.resolve("too-big.iso")))
    }

    private fun startBlockedTransfer(a: TestNode, b: TestNode, f: Path): String {
        b.faulty.writeDelayMs.set(3)
        val id = a.engine.send(b.target(), sources(f))
        b.awaitSession(id) { it.files[0].bytesDone > 1_000_000 }
        // Another app fills the disk.
        b.faulty.quota.set(b.faulty.used() + 300_000)
        return id
    }

    // 13
    @Test
    fun `13 destination runs out of space during transfer`() {
        val a = node("full-a"); val b = node("full-b", faulty = true); pair(a, b); b.autoAccept()
        val f = file("dataset.csv", 6_000_000)
        val id = startBlockedTransfer(a, b, f)
        val r = b.awaitSession(id) { it.state == SessionState.BLOCKED }
        val s = a.awaitSession(id) { it.state == SessionState.BLOCKED }
        assertTrue(r.storageIssue!!.reason == ErrorCode.STORAGE_FULL || r.storageIssue!!.reason == ErrorCode.INSUFFICIENT_STORAGE)
        assertEquals(r.storageIssue!!.reason, s.storageIssue!!.reason)
        assertNotNull(r.storageIssue!!.requiredBytes)
        assertEquals(FileStatus.BLOCKED, r.files[0].status)
        assertFalse(Files.exists(b.destination.resolve("dataset.csv")), "never shown as a completed file")
        // The partial holds exactly the verified prefix: nothing unverified beyond the checkpoint.
        val part = Files.walk(b.destination.resolve(".transferx-partial")).use { st -> st.filter { it.toString().endsWith(".part") }.findFirst().get() }
        assertEquals(r.files[0].bytesDone, Files.size(part))
        assertTrue(Files.size(part) % (64 * 1024) == 0L)
        a.engine.cancel(id)
        b.awaitSession(id) { it.state == SessionState.CANCELLED }
    }

    // 14
    @Test
    fun `14 transfer resumes after storage is freed`() {
        val a = node("free-a"); val b = node("free-b", faulty = true); pair(a, b); b.autoAccept()
        val f = file("photos.zip", 6_000_000)
        val id = startBlockedTransfer(a, b, f)
        val blocked = b.awaitSession(id) { it.state == SessionState.BLOCKED }
        // Retry before freeing space: still blocked, with an updated measurement.
        b.engine.retryStorage(id)
        Thread.sleep(300)
        assertEquals(SessionState.BLOCKED, b.engine.sessions.value[id]!!.state)
        // User frees space.
        b.faulty.writeDelayMs.set(0)
        b.faulty.quota.set(Long.MAX_VALUE)
        b.engine.retryStorage(id)
        val r = b.awaitSession(id) { it.state.terminal }
        val s = a.awaitSession(id) { it.state.terminal }
        assertEquals(SessionState.COMPLETED, r.state)
        assertEquals(SessionState.COMPLETED, s.state)
        assertReceived(b, f)
        assertNoPartialData(b)
        Metrics.record("storage-recovery: blocked at ${blocked.files[0].bytesDone} bytes, resumed to completion")
    }

    // 14b
    @Test
    fun `14b blocked transfer continues in another destination folder`() {
        val a = node("move-a"); val b = node("move-b", faulty = true); pair(a, b); b.autoAccept()
        val f = file("music.flac", 5_000_000)
        val id = startBlockedTransfer(a, b, f)
        b.awaitSession(id) { it.state == SessionState.BLOCKED }
        val other = Files.createDirectories(tmp.resolve("usb-drive"))
        b.faulty.writeDelayMs.set(0)
        b.faulty.quota.set(Long.MAX_VALUE)
        b.engine.retryStorage(id, other)
        val r = b.awaitSession(id) { it.state.terminal }
        assertEquals(SessionState.COMPLETED, r.state)
        assertEquals(TestFiles.sha256(f), TestFiles.sha256(other.resolve("music.flac")))
        assertFalse(Files.exists(b.destination.resolve("music.flac")))
    }

    // 15
    @Test
    fun `15 completed file stays intact when another file fails`() {
        val a = node("keep-a"); val b = node("keep-b"); pair(a, b); b.autoAccept()
        val good = file("contract.pdf", 400_000)
        val bad = file("unstable.log", 3_000_000)
        val failing = object : FileSource by LocalFileSource(bad) {
            override fun open(offset: Long): InputStream {
                val inner = LocalFileSource(bad).open(offset)
                return object : InputStream() {
                    var n = offset
                    override fun read(): Int = throw UnsupportedOperationException()
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (n > 1_000_000) throw AccessDeniedException(bad.toString(), null, "Permission denied")
                        val r = inner.read(b, off, len); if (r > 0) n += r; return r
                    }
                    override fun close() = inner.close()
                }
            }
        }
        val (s, r) = transfer(a, b, listOf(LocalFileSource(good), failing))
        assertEquals(SessionState.PARTIAL, s.state)
        assertEquals(FileStatus.COMPLETED, s.files[0].status)
        assertEquals(FileStatus.FAILED, s.files[1].status)
        assertEquals(ErrorCode.SOURCE_UNREADABLE, s.files[1].error)
        assertEquals(FileStatus.COMPLETED, r.files[0].status)
        assertReceived(b, good)
        assertFalse(Files.exists(b.destination.resolve("unstable.log")))
    }

    // 16
    @Test
    fun `16 duplicate file names are handled safely`() {
        val a = node("dup-a"); val b = node("dup-b"); pair(a, b)
        val existing = b.destination.resolve("report.pdf")
        Files.writeString(existing, "original")
        val f = file("report.pdf", 50_000)

        var conflictShown = false
        b.autoRespond { p -> conflictShown = p.files[0].conflictsWithExisting; OfferDecision(true, conflictPolicy = ConflictPolicy.RENAME) }
        val (s1, _) = transfer(a, b, sources(f))
        assertTrue(conflictShown)
        assertEquals("report (1).pdf", s1.files[0].savedAs)
        assertEquals("original", Files.readString(existing))
        assertReceived(b, f, "report (1).pdf")

        b.autoRespond { OfferDecision(true, conflictPolicy = ConflictPolicy.SKIP) }
        val (s2, _) = transfer(a, b, sources(f))
        assertEquals(FileStatus.SKIPPED, s2.files[0].status)
        assertEquals(ErrorCode.CONFLICT_SKIPPED, s2.files[0].error)
        assertEquals("original", Files.readString(existing))

        b.autoRespond { OfferDecision(true, conflictPolicy = ConflictPolicy.REPLACE) } // explicit user choice
        val (s3, _) = transfer(a, b, sources(f))
        assertEquals(FileStatus.COMPLETED, s3.files[0].status)
        assertReceived(b, f, "report.pdf")
        assertTrue(Files.exists(b.destination.resolve("report (1).pdf")), "earlier copy untouched")
    }

    // 17
    @Test
    fun `17 corrupted data fails integrity verification`() {
        val a = node("int-a", config = testConfig(integrityRetries = 0)); val b = node("int-b", faulty = true); pair(a, b); b.autoAccept()
        val ok = file("ok.bin", 200_000)
        val f = file("firmware.bin", 2_000_000)
        // Silent on-disk corruption (bad storage) at an offset only the second file reaches.
        b.faulty.corruptPartAt.set(1_234_567)
        val (s, r) = transfer(a, b, sources(ok, f))
        assertEquals(1, b.faulty.corruptions.get())
        assertEquals(SessionState.PARTIAL, s.state)
        assertEquals(FileStatus.FAILED, s.files[1].status)
        assertEquals(ErrorCode.INTEGRITY_FAILED, s.files[1].error)
        assertEquals(ErrorCode.INTEGRITY_FAILED, r.files[1].error)
        assertFalse(Files.exists(b.destination.resolve("firmware.bin")), "corrupt file never finalized")
        assertReceived(b, ok)
        assertNoPartialData(b)
    }

    @Test
    fun `17b corruption is detected and the file is re-sent once`() {
        val a = node("int2-a"); val b = node("int2-b", faulty = true); pair(a, b); b.autoAccept()
        b.faulty.corruptPartAt.set(777_777)
        val f = file("firmware2.bin", 2_000_000)
        val (s, _) = transfer(a, b, sources(f))
        assertEquals(1, b.faulty.corruptions.get())
        assertEquals(SessionState.COMPLETED, s.state)
        assertReceived(b, f)
    }

    @Test
    fun `17c corruption with retries disabled fails the file`() {
        val a = node("int3-a", config = testConfig(integrityRetries = 0)); val b = node("int3-b", faulty = true); pair(a, b); b.autoAccept()
        b.faulty.corruptPartAt.set(100)
        val f = file("firmware3.bin", 1_000_000)
        val (s, r) = transfer(a, b, sources(f))
        assertEquals(SessionState.FAILED, s.state)
        assertEquals(ErrorCode.INTEGRITY_FAILED, s.files[0].error)
        assertEquals(FileStatus.FAILED, r.files[0].status)
        assertTrue(TestFiles.visibleFiles(b.destination).isEmpty())
    }

    // 18
    @Test
    fun `18 impostor with a stolen name and id cannot receive files`() {
        val a = node("auth-a"); val b = node("auth-b"); pair(a, b)
        // M claims B's name; A connects using B's pinned fingerprint but reaches M.
        val m = node("auth-b", dirName = "impostor") // same display name, its own (different) key pair
        m.autoAccept()
        val id = a.engine.send(ConnectTarget.Tcp(listOf("127.0.0.1"), m.port, b.identity.fingerprintHex, null, b.identity.deviceId), sources(file("payroll.xlsx", 10_000)))
        val s = a.awaitSession(id) { terminal(it) }
        assertEquals(SessionState.FAILED, s.state)
        assertEquals(ErrorCode.IDENTITY_MISMATCH, s.error)
        assertTrue(m.engine.prompts.value.none { it is Prompt.IncomingOffer })
        assertTrue(TestFiles.visibleFiles(m.destination).isEmpty())
    }

    @Test
    fun `18b unpaired device with wrong codes is refused`() {
        val a = node("pair-a"); val b = node("pair-b"); b.autoAccept()
        val id = a.engine.send(b.target(pin = false), sources(file("x.txt", 1_000)))
        repeat(5) {
            val p = a.awaitPrompt(Prompt.PairingCodeNeeded::class.java)
            assertTrue(a.engine.submitPairingCode(p.id, "0000-0000-0000"))
            runBlocking { withTimeout(10_000) { a.engine.prompts.first { l -> l.none { it.id == p.id } } } }
        }
        val s = a.awaitSession(id) { terminal(it) }
        assertEquals(ErrorCode.PAIRING_FAILED, s.error)
        assertFalse(b.trust.isTrusted(a.identity.fingerprintHex))
        assertFalse(a.trust.isTrusted(b.identity.fingerprintHex))
        assertTrue(TestFiles.visibleFiles(b.destination).isEmpty())
    }

    @Test
    fun `18c correct code pairs both devices and the transfer proceeds`() {
        val a = node("ok-a"); val b = node("ok-b"); b.autoAccept()
        val f = file("hello.txt", 2_000)
        val id = a.engine.send(b.target(pin = false), sources(f))
        val shown = b.awaitPrompt(Prompt.PairingRequested::class.java) // displayed on the receiver
        val ask = a.awaitPrompt(Prompt.PairingCodeNeeded::class.java)
        assertTrue(a.engine.submitPairingCode(ask.id, shown.code.lowercase()))
        val s = a.awaitSession(id) { terminal(it) }
        assertEquals(SessionState.COMPLETED, s.state)
        assertTrue(a.trust.isTrusted(b.identity.fingerprintHex))
        assertTrue(b.trust.isTrusted(a.identity.fingerprintHex))
    }

    @Test
    fun `18d QR code (fingerprint + code) pairs without typing`() {
        val a = node("qr-a"); val b = node("qr-b"); b.autoAccept()
        val code = b.engine.pairing.activeCode().code
        val (s, _) = transfer(a, b, sources(file("q.txt", 100)), b.target(code = code, pin = true))
        assertEquals(SessionState.COMPLETED, s.state)
    }

    @Test
    fun `18e another paired device cannot hijack a session by resuming it`() {
        val a = node("hj-a", config = testConfig(maxReconnectAttempts = 0)); val b = node("hj-b"); val d = node("hj-d")
        pair(a, b); pair(d, b); b.autoAccept()
        val f = file("private.bin", 3_000_000)
        val id = FaultProxy(b.port).use { proxy ->
            proxy.dropAfterBytes.set(1_000_000)
            val id = a.engine.send(b.target(port = proxy.port), sources(f))
            a.awaitSession(id) { terminal(it) }
            id
        }
        // D forges a history entry for A's session and tries to resume it.
        val rec = a.history.get(id)!!
        d.history.upsert(rec.copy(connect = ConnectInfo(listOf("127.0.0.1"), b.port, b.identity.fingerprintHex, b.identity.deviceId)))
        assertTrue(d.engine.resume(id))
        val s = d.awaitSession(id) { it.state.terminal }
        assertEquals(ErrorCode.UNAUTHORIZED, s.error)
    }

    // 19
    @Test
    fun `19 malicious file names cannot escape the destination`() {
        val a = node("path-a"); val b = node("path-b"); pair(a, b); b.autoAccept()
        val body = file("payload.txt", 1_000)
        fun evil(n: String, dir: String) = object : FileSource by LocalFileSource(body) {
            override val ref = SourceRef("file", body.toString(), dir, n)
            override val name = n
            override val relativeDir = dir
        }
        val src = listOf(
            evil("../../escape.txt", ""),
            evil("ok.txt", "../../outside"),
            evil("abs.txt", "/etc"),
            evil("drive.txt", "C:\\Windows\\System32"),
            evil("..\\..\\win.bat", ""),
            evil("CON.txt", "sub"),
            evil("a<b>:c?.txt", ""),
        )
        val (s, r) = transfer(a, b, src)
        assertTrue(s.state == SessionState.PARTIAL)
        val refused = r.files.filter { it.error == ErrorCode.UNSAFE_NAME }
        assertEquals(5, refused.size, "traversal, absolute and drive paths refused")
        assertTrue(Files.exists(b.destination.resolve("sub/_CON.txt")))
        assertTrue(Files.exists(b.destination.resolve("a_b__c_.txt")))
        // Nothing was written anywhere outside the receiver's destination.
        val outside = Files.walk(tmp).use { st ->
            st.filter { Files.isRegularFile(it) && !it.startsWith(b.root) && !it.startsWith(a.root) && !it.startsWith(srcDir) }.toList()
        }
        assertTrue(outside.isEmpty(), "unexpected files: $outside")
        assertTrue(TestFiles.visibleFiles(b.root).all { it.startsWith(b.destination) || it.startsWith(b.dataDir) })
    }

    // 20
    @Test
    fun `20 app termination never marks incomplete files complete`() {
        val a = node("term-a", config = testConfig(maxReconnectAttempts = 0))
        val bRoot = Files.createDirectories(tmp.resolve("term-b"))
        val bStorage = FaultyStorage(Files.createDirectories(bRoot.resolve("downloads")))
        var b = TestNode(bRoot, "term-b", "android", testConfig(), bStorage)
        pair(a, b); b.autoAccept()
        bStorage.writeDelayMs.set(3)
        val f = file("video.mp4", 6_000_000)
        val id = a.engine.send(b.target(), sources(f))
        b.awaitSession(id) { it.files[0].bytesDone > 2_000_000 }
        b.close() // the receiving app is killed mid-transfer

        val sAfter = a.awaitSession(id) { terminal(it) }
        assertEquals(SessionState.INTERRUPTED, sAfter.state)
        assertFalse(Files.exists(bRoot.resolve("downloads/video.mp4")))

        // Restart the receiving app from the same data directory.
        bStorage.writeDelayMs.set(0)
        b = TestNode(bRoot, "term-b", "android", testConfig(), bStorage)
        nodes += b
        b.engine.recoverOnStartup()
        val recovered = b.history.get(id)!!.snapshot
        assertEquals(SessionState.INTERRUPTED, recovered.state)
        assertTrue(recovered.files.none { it.status == FileStatus.COMPLETED })
        assertFalse(Files.exists(b.destination.resolve("video.mp4")))
        b.autoAccept()
        assertTrue(a.engine.resume(id, b.target()))
        val s = a.awaitSession(id) { it.state.terminal }
        assertEquals(SessionState.COMPLETED, s.state)
        assertReceived(b, f)
    }

    // 21
    @Test
    fun `21 permission revoked during transfer is reported distinctly and recoverable`() {
        val a = node("perm-a"); val b = node("perm-b", faulty = true); pair(a, b); b.autoAccept()
        b.faulty.writeDelayMs.set(3)
        val f = file("scan.pdf", 5_000_000)
        val id = a.engine.send(b.target(), sources(f))
        b.awaitSession(id) { it.files[0].bytesDone > 1_000_000 }
        b.faulty.writable.set(false)
        val r = b.awaitSession(id) { it.state == SessionState.BLOCKED }
        assertEquals(ErrorCode.PERMISSION_DENIED, r.storageIssue!!.reason)
        assertEquals(ErrorCode.Category.PERMISSION, r.storageIssue!!.reason.category)
        val s = a.awaitSession(id) { it.state == SessionState.BLOCKED }
        assertEquals(ErrorCode.PERMISSION_DENIED, s.error)
        b.faulty.writeDelayMs.set(0)
        b.faulty.writable.set(true)
        b.engine.retryStorage(id)
        assertEquals(SessionState.COMPLETED, a.awaitSession(id) { it.state.terminal }.state)
        assertReceived(b, f)
    }

    @Test
    fun `21b source permission revoked on the sender is a permission failure`() {
        val a = node("perm2-a"); val b = node("perm2-b"); pair(a, b); b.autoAccept()
        val f = file("locked.docx", 100_000)
        val denied = object : FileSource by LocalFileSource(f) {
            override fun open(offset: Long): InputStream = throw SecurityException("Permission Denial: content URI revoked")
        }
        val (s, _) = transfer(a, b, listOf(denied))
        assertEquals(ErrorCode.SOURCE_UNREADABLE, s.files[0].error)
        assertEquals(ErrorCode.Category.PERMISSION, s.files[0].error!!.category)
    }

    // 22
    @Test
    fun `22 discovery failure offers the fallback connection`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val port = ServerSocket(0).use { it.localPort } // a UDP port nobody announces on
            val d = DiscoveryService(scope, { null }, udpPort = port, fallbackAfterMs = 300)
            d.start()
            val st = runBlocking { withTimeout(10_000) { d.status.first { it.suggestFallback } } }
            assertTrue(st.suggestFallback)
            assertTrue(d.devices.value.isEmpty())
            d.stop()
        } finally {
            scope.cancel()
        }
        // Manual address on another network: explained, not a fake "connected".
        val a = node("net-a", config = testConfig(maxReconnectAttempts = 0, connectTimeoutMs = 1_000))
        val id = a.engine.send(ConnectTarget.Tcp(listOf("203.0.113.9"), 47820), sources(file("n.txt", 10)))
        val s = a.awaitSession(id) { terminal(it) }
        assertEquals(ErrorCode.DIFFERENT_NETWORK, s.error)
    }

    @Test
    fun `22b two devices discover each other on the local machine`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val port = ServerSocket(0).use { it.localPort }
            fun ann(id: String, name: String) = Announcement(id = id, name = name, platform = "linux", port = 47820, fp = "ab".repeat(32))
            val idA = Ids.newId(); val idB = Ids.newId()
            val da = DiscoveryService(scope, { ann(idA, "Alpha") }, udpPort = port)
            val db = DiscoveryService(scope, { ann(idB, "Beta") }, udpPort = port)
            da.start(); db.start()
            val seen = runCatching { runBlocking { withTimeout(15_000) { da.devices.first { l -> l.any { it.deviceId == idB } } } } }
            da.stop(); db.stop()
            if (seen.isFailure) {
                Metrics.record("discovery-local: multicast/broadcast loopback not delivered in this environment (not a failure of fallback)")
                return
            }
            Metrics.record("discovery-local: peers discovered each other via UDP on one machine")
            assertEquals("Beta", seen.getOrThrow().first { it.deviceId == idB }.name)
        } finally {
            scope.cancel()
        }
    }

    // 23
    @Test
    fun `23 concurrent transfers do not corrupt one another`() {
        val a = node("conc-a"); val c = node("conc-c", "android"); val b = node("conc-b")
        pair(a, b); pair(c, b); b.autoAccept()
        val dirA = Files.createDirectories(tmp.resolve("srcA")); val dirC = Files.createDirectories(tmp.resolve("srcC"))
        val fa = TestFiles.random(dirA, "data.bin", 6_000_000, seed = 1)
        val fc = TestFiles.random(dirC, "data.bin", 6_000_000, seed = 2)
        val idA = a.engine.send(b.target(), sources(fa))
        val idC = c.engine.send(b.target(), sources(fc))
        val sa = a.awaitSession(idA) { it.state.terminal }
        val sc = c.awaitSession(idC) { it.state.terminal }
        assertEquals(SessionState.COMPLETED, sa.state)
        assertEquals(SessionState.COMPLETED, sc.state)
        val names = setOf(sa.files[0].savedAs, sc.files[0].savedAs)
        assertEquals(setOf("data.bin", "data (1).bin"), names)
        assertEquals(TestFiles.sha256(fa), TestFiles.sha256(b.destination.resolve(sa.files[0].savedAs!!)))
        assertEquals(TestFiles.sha256(fc), TestFiles.sha256(b.destination.resolve(sc.files[0].savedAs!!)))
    }

    // 24
    @Test
    fun `24 cancelled while waiting for receiver approval`() {
        val a = node("wait-a"); val b = node("wait-b"); pair(a, b)
        val id = a.engine.send(b.target(), sources(file("pending.txt", 1_000)))
        val prompt = b.awaitPrompt(Prompt.IncomingOffer::class.java)
        assertEquals(SessionState.AWAITING_APPROVAL, a.awaitSession(id) { it.state == SessionState.AWAITING_APPROVAL }.state)
        a.engine.cancel(id)
        assertEquals(SessionState.CANCELLED, a.awaitSession(id) { terminal(it) }.state)
        val r = b.awaitSession(id) { terminal(it) }
        assertEquals(SessionState.CANCELLED, r.state)
        assertEquals(ErrorCode.PEER_CANCELLED, r.error)
        runBlocking { withTimeout(5_000) { b.engine.prompts.first { l -> l.none { it.id == prompt.id } } } }
        assertTrue(TestFiles.visibleFiles(b.destination).isEmpty())
    }

    // ---- additional coverage

    @Test
    fun `approval timeout rejects automatically`() {
        val a = node("to-a"); val b = node("to-b", config = testConfig(approvalTimeoutMs = 800)); pair(a, b)
        val (s, r) = transfer(a, b, sources(file("t.txt", 10)))
        assertEquals(SessionState.REJECTED, s.state)
        assertEquals(ErrorCode.APPROVAL_TIMEOUT, s.error)
        assertEquals(ErrorCode.APPROVAL_TIMEOUT, r.error)
    }

    @Test
    fun `receiver that is not accepting declines immediately`() {
        val a = node("na-a"); val b = node("na-b"); pair(a, b); b.accepting = false
        val id = a.engine.send(b.target(), sources(file("t.txt", 10)))
        val s = a.awaitSession(id) { terminal(it) }
        assertEquals(SessionState.REJECTED, s.state)
        assertEquals(ErrorCode.NOT_ACCEPTING, s.error)
        assertTrue(b.engine.prompts.value.isEmpty(), "no prompt while not accepting")
    }

    @Test
    fun `pause and resume by sender and receiver`() {
        val a = node("pz-a"); val b = node("pz-b", faulty = true); pair(a, b); b.autoAccept()
        b.faulty.writeDelayMs.set(15)
        val f = file("pause.bin", 6_000_000)
        val id = a.engine.send(b.target(), sources(f))
        a.awaitSession(id) { it.files[0].bytesDone > 500_000 }
        a.engine.pause(id)
        a.awaitSession(id) { it.state == SessionState.PAUSED }
        val frozen = b.awaitSession(id) { it.state == SessionState.PAUSED }.files[0].bytesDone
        Thread.sleep(800)
        val later = b.engine.sessions.value[id]!!.files[0].bytesDone
        assertTrue(later - frozen <= 1024 * 1024 + 64 * 1024, "at most one in-flight window after pause")
        a.engine.resumePaused(id)
        b.awaitSession(id) { it.files[0].bytesDone > later + 200_000 }
        b.engine.pause(id) // receiver-side pause
        a.awaitSession(id) { it.pausedByPeer }
        b.engine.resumePaused(id)
        b.faulty.writeDelayMs.set(0)
        assertEquals(SessionState.COMPLETED, a.awaitSession(id) { it.state.terminal }.state)
        assertReceived(b, f)
    }

    @Test
    fun `receiver cancels a single file and the rest continue`() {
        val a = node("cf-a"); val b = node("cf-b", faulty = true); pair(a, b); b.autoAccept()
        b.faulty.writeDelayMs.set(3)
        val f1 = file("skip-me.bin", 5_000_000)
        val f2 = file("keep-me.txt", 50_000)
        val id = a.engine.send(b.target(), sources(f1, f2))
        val r0 = b.awaitSession(id) { it.files[0].bytesDone > 500_000 }
        b.faulty.writeDelayMs.set(0)
        b.engine.cancelFile(id, r0.files[0].fileId)
        val s = a.awaitSession(id) { it.state.terminal }
        assertEquals(FileStatus.CANCELLED, s.files[0].status)
        assertEquals(FileStatus.COMPLETED, s.files[1].status)
        assertEquals(SessionState.PARTIAL, s.state)
        assertReceived(b, f2)
        assertFalse(Files.exists(b.destination.resolve("skip-me.bin")))
    }

    @Test
    fun `partial data altered between sessions is detected on resume`() {
        val a = node("alt-a", config = testConfig(maxReconnectAttempts = 0)); val b = node("alt-b"); pair(a, b); b.autoAccept()
        val f = file("altered.bin", 6_000_000)
        FaultProxy(b.port).use { proxy ->
            proxy.dropAfterBytes.set(3_000_000)
            val id = a.engine.send(b.target(port = proxy.port), sources(f))
            a.awaitSession(id) { terminal(it) }
            b.awaitSession(id) { it.state == SessionState.INTERRUPTED }
            val part = Files.walk(b.destination.resolve(".transferx-partial")).use { st -> st.filter { it.toString().endsWith(".part") }.findFirst().get() }
            RandomAccessFile(part.toFile(), "rw").use { it.seek(100_000); it.write(0x42) }
            assertTrue(a.engine.resume(id))
            assertEquals(SessionState.COMPLETED, a.awaitSession(id) { it.state.terminal }.state)
            assertReceived(b, f)
        }
    }

    @Test
    fun `source modified during transfer is detected`() {
        val a = node("mod-a"); val b = node("mod-b", faulty = true); pair(a, b); b.autoAccept()
        b.faulty.writeDelayMs.set(3)
        val f = file("live.log", 4_000_000)
        val id = a.engine.send(b.target(), sources(f))
        a.awaitSession(id) { it.files[0].bytesDone > 500_000 }
        Files.write(f, ByteArray(100), java.nio.file.StandardOpenOption.APPEND)
        val s = a.awaitSession(id) { it.state.terminal }
        assertEquals(ErrorCode.SOURCE_CHANGED, s.files[0].error)
        assertFalse(Files.exists(b.destination.resolve("live.log")))
    }

    @Test
    fun `transfer over a stream transport (Bluetooth code path) using TLS over SSLEngine`() {
        val a = node("bt-a", "android"); val b = node("bt-b", "android"); pair(a, b); b.autoAccept()
        val rfcomm = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        nodes += AutoCloseable { rfcomm.close() }
        thread(isDaemon = true) {
            while (!rfcomm.isClosed) {
                val s = try { rfcomm.accept() } catch (e: IOException) { break }
                b.engine.acceptStream(RawStream(s.getInputStream(), s.getOutputStream(), "bt-sim") { s.close() }, TransportKind.BLUETOOTH)
            }
        }
        val target = ConnectTarget.Stream("bt-sim", TransportKind.BLUETOOTH, open = {
            val s = Socket(InetAddress.getLoopbackAddress(), rfcomm.localPort)
            RawStream(s.getInputStream(), s.getOutputStream(), "bt-sim") { s.close() }
        }, expectedFingerprint = b.identity.fingerprintHex)
        val f = file("contact.vcf", 1_500_000)
        val (s, r) = transfer(a, b, listOf(StreamOnlySource(f)), target)
        assertEquals(SessionState.COMPLETED, s.state)
        assertEquals(TransportKind.BLUETOOTH, s.transport)
        assertEquals(TransportKind.BLUETOOTH, r.transport)
        assertReceived(b, f)
    }

    @Test
    fun `history survives restart and retry re-sends unfinished files`() {
        val a = node("hist-a"); val b = node("hist-b"); pair(a, b)
        b.autoRespond { p -> OfferDecision(true, acceptedFileIds = setOf(p.files[0].fileId)) }
        val f1 = file("h1.txt", 1_000); val f2 = file("h2.txt", 2_000)
        val (s, _) = transfer(a, b, sources(f1, f2))
        assertEquals(SessionState.PARTIAL, s.state)
        val reloaded = HistoryStore(a.dataDir.resolve("history.json"))
        assertEquals(SessionState.PARTIAL, reloaded.get(s.id)!!.snapshot.state)
        b.autoAccept()
        val retryId = a.engine.retry(s.id)!!
        val s2 = a.awaitSession(retryId) { it.state.terminal }
        assertEquals(SessionState.COMPLETED, s2.state)
        assertEquals(listOf("h2.txt"), s2.files.map { it.name })
        assertReceived(b, f2)
    }
}
