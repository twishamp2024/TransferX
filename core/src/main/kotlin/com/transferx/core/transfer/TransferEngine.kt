package com.transferx.core.transfer

import com.transferx.core.discovery.NetUtils
import com.transferx.core.discovery.RateLimiter
import com.transferx.core.persistence.HistoryStore
import com.transferx.core.persistence.ReceiveIndex
import com.transferx.core.protocol.ErrorCode
import com.transferx.core.protocol.FileMeta
import com.transferx.core.protocol.FileStatus
import com.transferx.core.protocol.FramedConnection
import com.transferx.core.protocol.Offer
import com.transferx.core.protocol.ProtocolErrorMessage
import com.transferx.core.protocol.ResumeRequest
import com.transferx.core.protocol.TransferException
import com.transferx.core.security.DeviceIdentity
import com.transferx.core.security.PairingCodeManager
import com.transferx.core.security.PairingCodes
import com.transferx.core.security.TlsContext
import com.transferx.core.security.TlsStream
import com.transferx.core.security.TrustStore
import com.transferx.core.storage.FileNameSanitizer
import com.transferx.core.storage.NioStorageBackend
import com.transferx.core.storage.StorageBackend
import com.transferx.core.util.Ids
import com.transferx.core.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class LocalDevice(
    val name: String,
    val platform: String,
    val appVersion: String = "1.0.0",
    val transports: List<String> = listOf("wifi"),
)

data class ReceiveSettings(
    val destination: Path,
    val accepting: Boolean = true,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
)

/**
 * Entry point of the transfer system: owns the listener, active sessions, user prompts,
 * and recovery. All methods are thread-safe and non-blocking; work runs on [scope].
 */
class TransferEngine(
    val config: EngineConfig,
    val identity: DeviceIdentity,
    val trustStore: TrustStore,
    val history: HistoryStore,
    internal val receiveIndex: ReceiveIndex,
    val storage: StorageBackend = NioStorageBackend(),
    internal val localDevice: () -> LocalDevice,
    internal val receiveSettings: () -> ReceiveSettings,
    private val sourceResolver: SourceResolver = SourceResolver { ref ->
        if (ref.kind == "file") runCatching { LocalFileSource(Paths.get(ref.location), ref.relativeDir) }.getOrNull() else null
    },
    scope: CoroutineScope? = null,
) : Closeable {
    val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val tls = TlsContext(identity)
    val pairing = PairingCodeManager(config.pairingCodeTtlMs, config.maxPairingAttempts)
    internal val manifests = ManifestStore(storage)
    internal val finalizeLock = Any()

    /** Maps a device id to its currently known addresses (wired to discovery by the app). */
    @Volatile var addressResolver: (String) -> List<String>? = { null }

    /** Called after a received file is finalized (e.g. to trigger the Android media scanner). */
    @Volatile var onFileReceived: (Path, String?) -> Unit = { _, _ -> }

    private val _sessions = MutableStateFlow<Map<String, SessionSnapshot>>(emptyMap())

    /** Live (in-memory) session snapshots, keyed by session id. History holds the rest. */
    val sessions: StateFlow<Map<String, SessionSnapshot>> = _sessions.asStateFlow()

    private val _prompts = MutableStateFlow<List<Prompt>>(emptyList())
    val prompts: StateFlow<List<Prompt>> = _prompts.asStateFlow()

    private val _port = MutableStateFlow<Int?>(null)
    val listeningPort: StateFlow<Int?> = _port.asStateFlow()

    private val senders = ConcurrentHashMap<String, SenderSession>()
    private val receivers = ConcurrentHashMap<String, ReceiverSession>()
    private val offerPrompts = ConcurrentHashMap<String, Pair<ReceiverSession, CompletableDeferred<OfferDecision>>>()
    private val codeRequests = ConcurrentHashMap<String, Pair<String, CompletableDeferred<String?>>>()
    internal val pairingIos = ConcurrentHashMap<String, SessionIo>()
    private val deniedPairings: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val rateLimiter = RateLimiter(config.maxConnectionsPerMinutePerHost, 60_000)
    private val openConnections = AtomicInteger(0)
    @Volatile private var server: ServerSocket? = null

    // ------------------------------------------------------------------ listening

    /**
     * Starts accepting connections. If [port] is taken (e.g. a second instance on the same
     * machine) and [fallbackToAny] is set, an ephemeral port is used instead.
     */
    fun startListening(port: Int = EngineConfig.DEFAULT_PORT, bindAddress: InetAddress? = null, fallbackToAny: Boolean = true): Int {
        stopListening()
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(bindAddress, port), 50)
        } catch (e: BindException) {
            if (!fallbackToAny) {
                ss.close(); throw e
            }
            ss.bind(InetSocketAddress(bindAddress, 0), 50)
        }
        server = ss
        _port.value = ss.localPort
        Log.i(TAG, "Listening on port ${ss.localPort}")
        scope.launch(Dispatchers.IO) {
            while (!ss.isClosed) {
                val s = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                onAccepted(s)
            }
        }
        return ss.localPort
    }

    fun stopListening() {
        server?.let { runCatching { it.close() } }
        server = null
        _port.value = null
    }

    private fun onAccepted(s: Socket) {
        val remote = s.inetAddress
        if (!config.allowPublicAddresses && !NetUtils.isLocalNetworkAddress(remote)) {
            Log.w(TAG, "Refused connection from non-local address")
            runCatching { s.close() }
            return
        }
        if (!rateLimiter.tryAcquire(remote.hostAddress ?: "?")) {
            Log.w(TAG, "Rate limit exceeded for a remote host")
            runCatching { s.close() }
            return
        }
        if (openConnections.incrementAndGet() > config.maxConnections) {
            openConnections.decrementAndGet()
            runCatching { s.close() }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val t0 = System.nanoTime()
                val channel = try {
                    s.tcpNoDelay = true
                    val ssl = tls.wrapServer(s)
                    ssl.soTimeout = config.handshakeTimeoutMs
                    ssl.startHandshake()
                    val fp = TlsContext.peerFingerprint(ssl.session)
                    ssl.soTimeout = (config.idleTimeoutMs * 2).toInt()
                    SecureChannel(
                        FramedConnection(ssl.inputStream, ssl.outputStream, maxChunkBytes = EngineConfig.MAX_CHUNK) { runCatching { ssl.close() } },
                        fp, TransportKind.WIFI, remote.hostAddress,
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "TLS handshake with incoming connection failed: ${e.javaClass.simpleName}")
                    runCatching { s.close() }
                    return@launch
                }
                Log.i(TAG, "TLS handshake with incoming connection took ${(System.nanoTime() - t0) / 1_000_000} ms")
                serveChannel(channel)
            } finally {
                openConnections.decrementAndGet()
            }
        }
    }

    /** Hands an incoming stream connection (e.g. Bluetooth RFCOMM) to the engine. */
    fun acceptStream(raw: RawStream, transport: TransportKind) {
        scope.launch(Dispatchers.IO) {
            val channel = try {
                secureStream(raw, client = false, transport)
            } catch (e: Exception) {
                Log.w(TAG, "TLS over ${transport.name.lowercase()} failed", e)
                runCatching { raw.close() }
                return@launch
            }
            serveChannel(channel)
        }
    }

    private suspend fun serveChannel(ch: SecureChannel) {
        val io = SessionIo(ch.conn, scope, config.pingIntervalMs, config.idleTimeoutMs, capacity = 4).start()
        try {
            val t0 = System.nanoTime()
            val (peer, first) = serverHandshake(io, ch)
            Log.i(TAG, "Authenticated ${peer.name} over ${ch.transport.name.lowercase()} in ${(System.nanoTime() - t0) / 1_000_000} ms")
            val receiver = ReceiverSession(this, io, peer, ch.transport)
            when (first) {
                is Offer -> receiver.handleOffer(first)
                is ResumeRequest -> receiver.handleResume(first)
                else -> io.conn.send(ProtocolErrorMessage(ErrorCode.PROTOCOL_ERROR, "expected offer"))
            }
        } catch (e: TransferException) {
            Log.w(TAG, "Incoming connection ended: ${e.code} ${e.message ?: ""}")
            if (e.code == ErrorCode.PROTOCOL_ERROR || e.code == ErrorCode.UNSUPPORTED_VERSION) {
                runCatching { io.conn.send(ProtocolErrorMessage(e.code, "")) }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Incoming connection I/O: ${e.javaClass.simpleName}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Incoming connection failed", e)
        } finally {
            // Our final message (result, rejection, cancel) must reach the sender: wait for it to close first.
            runCatching { io.drainUntilClosed(2_000) }
            io.close()
        }
    }

    // ------------------------------------------------------------------ outgoing

    internal suspend fun openChannel(target: ConnectTarget): SecureChannel = when (target) {
        is ConnectTarget.Tcp -> connectTcp(target)
        is ConnectTarget.Stream -> {
            val raw = try {
                target.open()
            } catch (e: Exception) {
                throw TransferException(ErrorCode.CONNECT_FAILED, cause = e)
            }
            try {
                secureStream(raw, client = true, target.transport)
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw TransferException(ErrorCode.CONNECT_FAILED, cause = e)
            }
        }
    }

    private suspend fun connectTcp(t: ConnectTarget.Tcp): SecureChannel {
        var last: Exception? = null
        for (host in t.hosts.distinct()) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                runInterruptible(Dispatchers.IO) { s.connect(InetSocketAddress(host, t.port), config.connectTimeoutMs) }
                val ssl = tls.wrapClient(s, host, t.port)
                ssl.soTimeout = config.handshakeTimeoutMs
                runInterruptible(Dispatchers.IO) { ssl.startHandshake() }
                val fp = TlsContext.peerFingerprint(ssl.session)
                ssl.soTimeout = (config.idleTimeoutMs * 2).toInt()
                return SecureChannel(
                    FramedConnection(ssl.inputStream, ssl.outputStream, maxChunkBytes = EngineConfig.MAX_CHUNK) { runCatching { ssl.close() } },
                    fp, TransportKind.WIFI, host,
                )
            } catch (e: Exception) {
                last = e
                runCatching { s.close() }
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        val differentNetwork = t.hosts.isNotEmpty() && t.hosts.all { NetUtils.isOnLocalSubnet(it) == false }
        throw TransferException(if (differentNetwork) ErrorCode.DIFFERENT_NETWORK else ErrorCode.CONNECT_FAILED, cause = last)
    }

    private suspend fun secureStream(raw: RawStream, client: Boolean, transport: TransportKind): SecureChannel {
        val engine = tls.newEngine(client)
        val stream = TlsStream(engine, raw.input, raw.output, raw.close)
        val watchdog = scope.launch {
            delay(config.handshakeTimeoutMs.toLong())
            runCatching { raw.close() }
        }
        try {
            runInterruptible(Dispatchers.IO) { stream.handshake() }
        } finally {
            watchdog.cancel()
        }
        val fp = TlsContext.peerFingerprint(engine.session)
        return SecureChannel(
            FramedConnection(stream.input, stream.output, maxChunkBytes = EngineConfig.MAX_CHUNK) { stream.close() },
            fp, transport, raw.label,
        )
    }

    internal fun refreshTarget(target: ConnectTarget): ConnectTarget? {
        if (target !is ConnectTarget.Tcp) return null
        val id = target.deviceId ?: return null
        val fresh = addressResolver(id) ?: return null
        return target.copy(hosts = (fresh + target.hosts).distinct())
    }

    /**
     * Starts sending [sources] to [target]. Returns the session id immediately. Progress
     * appears in [sessions], and pairing/approval needs appear in [prompts].
     */
    fun send(target: ConnectTarget, sources: List<FileSource>, peerLabel: String? = null): String {
        require(sources.isNotEmpty()) { "nothing to send" }
        require(sources.size <= config.maxFilesPerOffer) { "too many files" }
        val id = Ids.newId()
        val metas = sources.map { FileMeta(Ids.newId(), it.name, it.relativeDir, it.size, it.mimeType, it.modifiedAt) }
        val files = metas.map { FileProgress(it.fileId, it.name, it.relativeDir, it.size, it.mimeType) }
        val address = (target as? ConnectTarget.Tcp)?.hosts?.firstOrNull() ?: (target as? ConnectTarget.Stream)?.label
        val peer = PeerInfo(target.deviceId ?: "", peerLabel ?: address ?: "Device", "unknown", target.expectedFingerprint ?: "", address)
        val session = SenderSession(this, id, sources, metas, target, config.chunkSize, files, System.currentTimeMillis(), false, peer)
        launchSender(session)
        return id
    }

    /** Resumes an interrupted outgoing session. [target] overrides the stored address (e.g. Bluetooth). */
    fun resume(sessionId: String, target: ConnectTarget? = null): Boolean {
        if (senders.containsKey(sessionId)) return false
        val rec = history.get(sessionId) ?: return false
        if (rec.snapshot.direction != Direction.SEND || !rec.snapshot.resumable || rec.offered.isEmpty()) return false
        val connect = rec.connect
        val t = target ?: connect?.let {
            val base = ConnectTarget.Tcp(it.hosts, it.port, it.fingerprint, null, it.deviceId)
            refreshTarget(base) ?: base
        } ?: return false
        val sources = rec.sources.map { sourceResolver.resolve(it) }
        val files = rec.snapshot.files.map { if (it.status.terminal) it else it.copy(status = FileStatus.INTERRUPTED) }
        val session = SenderSession(
            this, sessionId, sources, rec.offered, t, rec.chunkSize, files, rec.snapshot.createdAt, true, rec.snapshot.peer,
        )
        session.persistedRefs = rec.sources
        session.initialConnectInfo = connect
        launchSender(session)
        return true
    }

    /** Sends the unfinished files of a finished session again, as a new session. Returns its id. */
    fun retry(sessionId: String, target: ConnectTarget? = null): String? {
        val rec = history.get(sessionId) ?: return null
        if (rec.snapshot.direction != Direction.SEND) return null
        val connect = rec.connect
        val t = target ?: connect?.let {
            val base = ConnectTarget.Tcp(it.hosts, it.port, it.fingerprint, null, it.deviceId)
            refreshTarget(base) ?: base
        } ?: return null
        val sources = rec.snapshot.files.mapIndexedNotNull { i, f ->
            if (f.status == FileStatus.COMPLETED) null else rec.sources.getOrNull(i)?.let { sourceResolver.resolve(it) }
        }
        if (sources.isEmpty()) return null
        return send(t, sources, rec.snapshot.peer.name)
    }

    private fun launchSender(session: SenderSession) {
        senders[session.id] = session
        publish(session.record(), persist = true)
        scope.launch(Dispatchers.IO) { session.run() }
    }

    internal fun senderFinished(session: SenderSession) {
        senders.remove(session.id)
        codeRequests.entries.removeIf { (k, v) ->
            if (v.first == session.id) {
                v.second.complete(null); removePrompt(k); true
            } else false
        }
    }

    // ------------------------------------------------------------------ session control

    fun pause(sessionId: String) {
        senders[sessionId]?.commands?.trySend(SenderCommand.Pause)
        receivers[sessionId]?.commands?.trySend(ReceiverCommand.Pause)
    }

    fun resumePaused(sessionId: String) {
        senders[sessionId]?.commands?.trySend(SenderCommand.Resume)
        receivers[sessionId]?.commands?.trySend(ReceiverCommand.Resume)
    }

    /**
     * Cancels a session. For a live session the peer is told. For an interrupted one the
     * stored state is discarded (and on the receiver, its temporary data deleted).
     * Completed files are never deleted.
     */
    fun cancel(sessionId: String) {
        val s = senders[sessionId]
        if (s != null) {
            s.commands.trySend(SenderCommand.Cancel)
            codeRequests.entries.filter { it.value.first == sessionId }.forEach { it.value.second.complete(null) }
            return
        }
        val r = receivers[sessionId]
        if (r != null) {
            r.commands.trySend(ReceiverCommand.Cancel)
            return
        }
        discardStored(sessionId)
    }

    fun cancelFile(sessionId: String, fileId: String) {
        senders[sessionId]?.commands?.trySend(SenderCommand.CancelFile(fileId))
        receivers[sessionId]?.commands?.trySend(ReceiverCommand.CancelFile(fileId))
    }

    /** Re-checks storage for a blocked incoming session, optionally switching destination. */
    fun retryStorage(sessionId: String, newDestination: Path? = null) {
        receivers[sessionId]?.commands?.trySend(ReceiverCommand.RetryStorage(newDestination))
    }

    private fun discardStored(sessionId: String) {
        val dir = receiveIndex.get(sessionId)
        if (dir != null) {
            deletePartialDir(dir)
            receiveIndex.remove(sessionId)
        }
        history.update(sessionId) { rec ->
            val files = rec.snapshot.files.map { if (it.status.terminal) it else it.copy(status = FileStatus.CANCELLED, error = ErrorCode.USER_CANCELLED) }
            rec.copy(snapshot = rec.snapshot.copy(state = SenderSession.finalState(files).let { st ->
                if (st == SessionState.INTERRUPTED) SessionState.CANCELLED else st
            }, files = files, error = ErrorCode.USER_CANCELLED, updatedAt = System.currentTimeMillis()))
        }
        _sessions.update { it - sessionId }
    }

    private fun deletePartialDir(dir: Path) {
        try {
            if (!storage.exists(dir) || storage.isSymbolicLink(dir)) return
            if (dir.parent?.fileName?.toString() != FileNameSanitizer.PARTIAL_DIR) return // only ever delete our own dirs
            for (child in storage.list(dir)) if (!storage.isDirectory(child)) storage.deleteIfExists(child)
            storage.deleteIfExists(dir)
            dir.parent?.let { if (storage.list(it).isEmpty()) storage.deleteIfExists(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete temporary data", e)
        }
    }

    // ------------------------------------------------------------------ prompts

    internal fun addPrompt(p: Prompt) = _prompts.update { it + p }
    internal fun removePrompt(id: String) = _prompts.update { list -> list.filter { it.id != id } }
    private fun replacePrompt(p: Prompt) = _prompts.update { list -> list.map { if (it.id == p.id) p else it } }

    internal fun registerOfferPrompt(id: String, r: ReceiverSession, d: CompletableDeferred<OfferDecision>) {
        offerPrompts[id] = r to d
    }

    internal fun unregisterOfferPrompt(id: String) {
        offerPrompts.remove(id)
    }

    fun respondToOffer(promptId: String, decision: OfferDecision) {
        offerPrompts[promptId]?.second?.complete(decision)
    }

    /** Re-runs the storage preflight for a pending offer against another destination. */
    fun recheckOffer(promptId: String, destination: Path) {
        val (r, _) = offerPrompts[promptId] ?: return
        val existing = _prompts.value.firstOrNull { it.id == promptId } as? Prompt.IncomingOffer ?: return
        replacePrompt(r.buildOfferPrompt(promptId, destination, existing.expiresAt))
    }

    internal suspend fun requestPairingCode(sessionId: String, peer: PeerInfo, attemptsLeft: Int, failedBefore: Boolean): String? {
        val id = Ids.newId()
        val d = CompletableDeferred<String?>()
        codeRequests[id] = sessionId to d
        addPrompt(Prompt.PairingCodeNeeded(id, sessionId, peer, attemptsLeft, failedBefore))
        try {
            return withTimeoutOrNull(config.pairingCodeTtlMs) { d.await() }
        } finally {
            codeRequests.remove(id)
            removePrompt(id)
        }
    }

    /** Supplies the code the user typed. Returns false (and keeps the prompt) if it isn't a valid code. */
    fun submitPairingCode(promptId: String, code: String?): Boolean {
        val req = codeRequests[promptId] ?: return false
        if (code == null) {
            req.second.complete(null); return true
        }
        val normalized = PairingCodes.normalize(code) ?: return false
        req.second.complete(normalized)
        return true
    }

    /** Receiver rejects a pairing request; the connection is closed. */
    fun denyPairing(promptId: String) {
        deniedPairings += promptId
        pairingIos.remove(promptId)?.close()
        removePrompt(promptId)
    }

    internal fun isPairingDenied(promptId: String) = promptId in deniedPairings

    // ------------------------------------------------------------------ registry

    internal fun registerReceiver(r: ReceiverSession): Boolean = synchronized(receivers) {
        if (receivers.size >= config.maxConcurrentIncoming || receivers.containsKey(r.id)) return false
        receivers[r.id] = r
        true
    }

    internal fun unregisterReceiver(r: ReceiverSession) {
        receivers.remove(r.id, r)
    }

    /**
     * A sender that reconnects after a network drop may arrive before we noticed the old
     * connection died. If the same authenticated peer resumes, the stale session is
     * closed (it saves its checkpoint) and the new connection takes over.
     */
    internal suspend fun takeOverStaleReceiver(id: String, peerFingerprint: String): Boolean {
        val old = receivers[id] ?: return true
        if (old.peerFingerprint != peerFingerprint) return false
        Log.i(TAG, "Replacing stale connection for session ${id.take(6)}")
        old.abortStale()
        withTimeoutOrNull(5_000) { while (receivers[id] === old) delay(25) }
        return receivers[id] == null
    }

    internal fun isReceiveSessionKnown(id: String): Boolean =
        receivers.containsKey(id) || receiveIndex.get(id) != null || history.get(id) != null || senders.containsKey(id)

    /** Bytes other active incoming sessions still need on the same volume as [dest]. */
    internal fun committedBytes(dest: Path, excludeId: String): Long = receivers.values
        .filter { it.id != excludeId && it.destinationOrNull()?.toAbsolutePath()?.root == dest.toAbsolutePath().root }
        .sumOf { it.committedRemainingBytes() }

    internal fun publish(record: TransferRecord, persist: Boolean) {
        val snap = record.snapshot
        if (snap.id.isEmpty()) return
        // Persist first: anyone observing a terminal state may immediately rely on history.
        if (persist) history.upsert(record)
        _sessions.update { it + (snap.id to snap) }
    }

    // ------------------------------------------------------------------ recovery

    /**
     * Run once at startup. Anything that was live when the app stopped becomes INTERRUPTED,
     * never COMPLETED. Receive data past the retention period is deleted.
     */
    fun recoverOnStartup() {
        val now = System.currentTimeMillis()
        for (rec in history.records.value) {
            val s = rec.snapshot
            if (!s.state.terminal && s.state != SessionState.INTERRUPTED && !senders.containsKey(s.id) && !receivers.containsKey(s.id)) {
                history.upsert(rec.copy(snapshot = s.copy(
                    state = SessionState.INTERRUPTED,
                    files = s.files.map { if (it.status.terminal) it else it.copy(status = FileStatus.INTERRUPTED) },
                    updatedAt = now,
                )))
            }
        }
        for ((sid, dir) in receiveIndex.all()) {
            val m = manifests.load(dir)
            if (m == null) {
                receiveIndex.remove(sid); continue
            }
            if (now - m.updatedAt > config.partialRetentionMs) {
                Log.i(TAG, "Removing expired temporary data for session ${sid.take(6)}")
                deletePartialDir(dir)
                receiveIndex.remove(sid)
                history.update(sid) { r ->
                    r.copy(snapshot = r.snapshot.copy(
                        state = SessionState.FAILED, error = ErrorCode.EXPIRED,
                        files = r.snapshot.files.map { if (it.status.terminal) it else it.copy(status = FileStatus.FAILED, error = ErrorCode.EXPIRED) },
                    ))
                }
                continue
            }
            if (history.get(sid) == null) {
                history.upsert(TransferRecord(SessionSnapshot(
                    id = sid, direction = Direction.RECEIVE, transport = m.transport, peer = m.peer,
                    state = SessionState.INTERRUPTED,
                    files = m.files.map {
                        FileProgress(it.fileId, it.name, it.relativeDir, it.size, it.mimeType, 0,
                            if (it.status.terminal) it.status else FileStatus.INTERRUPTED, it.error)
                    },
                    createdAt = m.createdAt, updatedAt = now, destination = m.destination,
                )))
            }
        }
        sweepOrphans(receiveSettings().destination)
    }

    /** Deletes `.transferx-partial/<id>` folders no session refers to, once they're old enough. */
    fun sweepOrphans(destination: Path) {
        val root = destination.resolve(FileNameSanitizer.PARTIAL_DIR)
        try {
            if (!storage.isDirectory(root) || storage.isSymbolicLink(root)) return
            val known = receiveIndex.all().values.map { it.toAbsolutePath().normalize() }.toSet()
            val now = System.currentTimeMillis()
            for (dir in storage.list(root)) {
                val name = dir.fileName.toString()
                if (!Ids.isValid(name) || dir.toAbsolutePath().normalize() in known) continue
                if (now - storage.lastModified(dir) > config.partialRetentionMs) deletePartialDir(dir)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Orphan sweep failed", e)
        }
    }

    override fun close() {
        stopListening()
        pairingIos.values.forEach { it.close() }
        scope.cancel()
    }

    companion object {
        private const val TAG = "Engine"
    }
}
