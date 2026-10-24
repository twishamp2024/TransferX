package com.transferx.core.transfer

import com.transferx.core.protocol.Ack
import com.transferx.core.protocol.Blocked
import com.transferx.core.protocol.CancelFile
import com.transferx.core.protocol.CancelSession
import com.transferx.core.protocol.ErrorCode
import com.transferx.core.protocol.FileEnd
import com.transferx.core.protocol.FileMeta
import com.transferx.core.protocol.FileResult
import com.transferx.core.protocol.FileStart
import com.transferx.core.protocol.FileStatus
import com.transferx.core.protocol.Frame
import com.transferx.core.protocol.Message
import com.transferx.core.protocol.Offer
import com.transferx.core.protocol.OfferResponse
import com.transferx.core.protocol.Pause
import com.transferx.core.protocol.ProtocolErrorMessage
import com.transferx.core.protocol.Resend
import com.transferx.core.protocol.ResumeRequest
import com.transferx.core.protocol.ResumeState
import com.transferx.core.protocol.ResumeTransfer
import com.transferx.core.protocol.RestartFile
import com.transferx.core.protocol.SessionEnd
import com.transferx.core.protocol.TransferException
import com.transferx.core.protocol.Unblocked
import com.transferx.core.storage.IoErrorClassifier
import com.transferx.core.util.Hashing
import com.transferx.core.util.Hex
import com.transferx.core.util.Log
import com.transferx.core.util.SpeedMeter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream

sealed class SenderCommand {
    data object Cancel : SenderCommand()
    data class CancelFile(val fileId: String) : SenderCommand()
    data object Pause : SenderCommand()
    data object Resume : SenderCommand()
}

internal class SessionCancelled(val code: ErrorCode) : Exception(code.userMessage)

/**
 * Sending side of one transfer session. Drives: connect → authenticate → offer (or
 * resume) → per-file streaming with acknowledgements → final verification result
 * from the receiver. Reconnects automatically after network drops once the receiver
 * has accepted, then continues through the resume protocol.
 */
internal class SenderSession(
    private val engine: TransferEngine,
    val id: String,
    private val sources: List<FileSource?>,
    private val metas: List<FileMeta>,
    private var target: ConnectTarget,
    private val chunkSize: Int,
    initialFiles: List<FileProgress>,
    private val createdAt: Long,
    resumeMode: Boolean,
    initialPeer: PeerInfo,
) {
    private val cfg = engine.config
    val commands = Channel<SenderCommand>(Channel.UNLIMITED)

    private val files = initialFiles.toMutableList()
    @Volatile private var state = SessionState.CONNECTING
    private var peer = initialPeer
    private var transport = if (target is ConnectTarget.Stream) (target as ConnectTarget.Stream).transport else TransportKind.WIFI
    private var error: ErrorCode? = null
    private var errorDetail: String? = null
    private var storageIssue: StorageIssue? = null
    private var pausedByUser = false
    private var pausedByPeer = false
    private var accepted = resumeMode
    private var reconnectAttempt = 0
    private var connectInfo: ConnectInfo? = null
    private var io: SessionIo? = null
    private val speed = SpeedMeter()
    private var bytesOnWire = 0L
    private var lastPublishNanos = 0L
    private val resumeOffsets = LongArray(metas.size)
    private val resumeDigests = arrayOfNulls<String>(metas.size)

    private sealed class Ev {
        data class Msg(val m: Message) : Ev()
        data class Cmd(val c: SenderCommand) : Ev()
        data object Timeout : Ev()
    }

    private sealed class Outcome {
        data class Done(val result: FileResult) : Outcome()
        data class Rewind(val offset: Long, val chunkFailure: Boolean) : Outcome()
        data class Cancelled(val code: ErrorCode) : Outcome()
        data class LocalFailure(val code: ErrorCode) : Outcome()
    }

    fun snapshot(): SessionSnapshot = synchronized(this) {
        val remaining = files.filter { !it.status.terminal }.sumOf { (it.size - it.bytesDone).coerceAtLeast(0) }
        val live = state == SessionState.TRANSFERRING
        SessionSnapshot(
            id = id, direction = Direction.SEND, transport = transport, peer = peer, state = state,
            files = files.toList(), createdAt = createdAt, updatedAt = System.currentTimeMillis(),
            bytesPerSecond = if (live) speed.bytesPerSecond() else null,
            etaSeconds = if (live) speed.etaSeconds(remaining) else null,
            error = error, errorDetail = errorDetail, storageIssue = storageIssue,
            pausedByPeer = pausedByPeer, reconnectAttempt = reconnectAttempt,
        )
    }

    fun record(): TransferRecord = TransferRecord(
        snapshot(),
        sources = sources.mapIndexed { i, s -> s?.ref ?: persistedRefs.getOrNull(i) ?: SourceRef("missing", "", metas[i].relativeDir, metas[i].name) },
        offered = metas,
        connect = connectInfo,
        chunkSize = chunkSize,
    )

    /** Refs from the history record, kept when a source can't be reopened on resume. */
    var persistedRefs: List<SourceRef> = emptyList()
    var initialConnectInfo: ConnectInfo?
        get() = connectInfo
        set(v) {
            connectInfo = v
        }

    private fun publish(persist: Boolean = false) {
        val now = System.nanoTime()
        if (!persist && now - lastPublishNanos < 100_000_000L) return
        lastPublishNanos = now
        engine.publish(record(), persist)
    }

    private fun setFile(i: Int, transform: (FileProgress) -> FileProgress) = synchronized(this) { files[i] = transform(files[i]) }

    suspend fun run() {
        try {
            while (true) {
                try {
                    connectAndTransfer()
                    return
                } catch (raw: Exception) {
                    val e = when (raw) {
                        is TransferException -> raw
                        is IOException -> io?.networkError(raw) ?: TransferException(ErrorCode.NETWORK, cause = raw)
                        else -> throw raw
                    }
                    closeIo()
                    if (e.code.category == ErrorCode.Category.NETWORK && accepted && reconnectAttempt < cfg.maxReconnectAttempts) {
                        reconnectAttempt++
                        Log.w(TAG, "Session ${id.take(6)}: ${e.code}, reconnect attempt $reconnectAttempt")
                        markActiveFiles(FileStatus.INTERRUPTED)
                        state = SessionState.CONNECTING
                        error = e.code
                        publish(persist = true)
                        if (waitOrCancel(cfg.reconnectBackoffMs.getOrElse(reconnectAttempt - 1) { 10_000 })) {
                            throw SessionCancelled(ErrorCode.USER_CANCELLED)
                        }
                        engine.refreshTarget(target)?.let { target = it }
                        continue
                    }
                    throw e
                }
            }
        } catch (c: SessionCancelled) {
            if (c.code == ErrorCode.USER_CANCELLED) io?.drainUntilClosed(3_000) // let the receiver read our cancel
            markActiveFiles(FileStatus.CANCELLED, c.code)
            state = SessionState.CANCELLED
            error = c.code
        } catch (e: TransferException) {
            val canResume = accepted && e.code.resumable
            markActiveFiles(if (canResume) FileStatus.INTERRUPTED else FileStatus.FAILED, e.code)
            state = when {
                canResume -> SessionState.INTERRUPTED
                e.code == ErrorCode.REJECTED || e.code == ErrorCode.APPROVAL_TIMEOUT || e.code == ErrorCode.NOT_ACCEPTING ||
                    e.code == ErrorCode.BUSY -> SessionState.REJECTED
                e.code == ErrorCode.USER_CANCELLED -> SessionState.CANCELLED
                else -> SessionState.FAILED
            }
            error = e.code
            errorDetail = e.message?.takeIf { it != e.code.userMessage }
            Log.w(TAG, "Session ${id.take(6)} ended: ${e.code}")
        } catch (e: CancellationException) {
            markActiveFiles(FileStatus.INTERRUPTED, ErrorCode.NETWORK)
            state = SessionState.INTERRUPTED
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Session ${id.take(6)} internal error", e)
            markActiveFiles(if (accepted) FileStatus.INTERRUPTED else FileStatus.FAILED, ErrorCode.INTERNAL)
            state = if (accepted) SessionState.INTERRUPTED else SessionState.FAILED
            error = ErrorCode.INTERNAL
            errorDetail = e.javaClass.simpleName
        } finally {
            closeIo()
            storageIssue = if (state == SessionState.BLOCKED) storageIssue else null
            publish(persist = true)
            engine.senderFinished(this)
        }
    }

    private suspend fun connectAndTransfer() {
        state = SessionState.CONNECTING
        publish(persist = true)
        val channel = engine.openChannel(target)
        transport = channel.transport
        val io = SessionIo(channel.conn, engine.scope, cfg.pingIntervalMs, cfg.idleTimeoutMs, capacity = 256).start()
        this.io = io
        peer = engine.clientHandshake(io, channel, target, id) {
            state = SessionState.PAIRING
            publish(persist = true)
        }
        // From now on reconnects pin this identity and need no code.
        target = when (val t = target) {
            is ConnectTarget.Tcp -> {
                val hosts = (listOfNotNull(channel.remoteAddress) + t.hosts).distinct()
                connectInfo = ConnectInfo(hosts, t.port, peer.fingerprint, peer.deviceId)
                t.copy(hosts = hosts, expectedFingerprint = peer.fingerprint, pairingCode = null, deviceId = peer.deviceId)
            }
            is ConnectTarget.Stream -> ConnectTarget.Stream(t.label, t.transport, t.open, peer.fingerprint, null, peer.deviceId)
        }

        if (accepted) {
            io.conn.send(ResumeRequest(id))
            val rs = awaitMessage<ResumeState>(cfg.handshakeTimeoutMs.toLong() + 60_000)
            if (!rs.ok) {
                if (rs.error == ErrorCode.BUSY) throw TransferException(ErrorCode.NETWORK, "receiver busy; will retry")
                accepted = false // the receiver can't continue this session
                throw TransferException(rs.error ?: ErrorCode.SESSION_NOT_FOUND)
            }
            applyResumeState(rs)
        } else {
            io.conn.send(Offer(id, chunkSize, metas))
            state = SessionState.AWAITING_APPROVAL
            publish(persist = true)
            val resp = awaitMessage<OfferResponse>(cfg.approvalTimeoutMs + 30_000)
            if (!resp.accepted) {
                synchronized(this) {
                    for (i in files.indices) files[i] = files[i].copy(status = FileStatus.SKIPPED, error = resp.reason ?: ErrorCode.REJECTED)
                }
                throw TransferException(resp.reason ?: ErrorCode.REJECTED)
            }
            accepted = true
            val ids = resp.acceptedFileIds.toSet()
            for (i in metas.indices) {
                if (metas[i].fileId !in ids) setFile(i) { it.copy(status = FileStatus.SKIPPED, error = ErrorCode.NOT_SELECTED) }
            }
        }

        state = SessionState.TRANSFERRING
        error = null
        reconnectAttempt = 0
        publish(persist = true)
        for (i in metas.indices) {
            if (files[i].status.terminal) continue
            sendFile(i)
            publish(persist = true)
        }
        io.conn.send(SessionEnd(id))
        withTimeoutOrNull(10_000) {
            while (true) {
                val ev = nextEvent(10_000)
                if (ev is Ev.Msg && ev.m is SessionEnd) break
            }
        }
        state = finalState(files)
    }

    private fun applyResumeState(rs: ResumeState) {
        val byId = rs.files.associateBy { it.fileId }
        for (i in metas.indices) {
            val info = byId[metas[i].fileId]
            val f = files[i]
            when {
                info == null -> if (!f.status.terminal) setFile(i) { it.copy(status = FileStatus.SKIPPED, error = ErrorCode.NOT_SELECTED) }
                info.status == FileStatus.COMPLETED -> setFile(i) { it.copy(status = FileStatus.COMPLETED, bytesDone = it.size, error = null) }
                info.status.terminal -> setFile(i) { it.copy(status = info.status, error = info.error) }
                else -> {
                    resumeOffsets[i] = info.verifiedOffset
                    resumeDigests[i] = info.prefixDigest
                    setFile(i) { it.copy(status = FileStatus.PENDING, bytesDone = info.verifiedOffset, error = null) }
                }
            }
        }
    }

    private suspend fun sendFile(i: Int) {
        val meta = metas[i]
        val src = sources[i]
        if (src == null) {
            io!!.conn.send(CancelFile(meta.fileId, ErrorCode.SOURCE_UNREADABLE))
            setFile(i) { it.copy(status = FileStatus.FAILED, error = ErrorCode.SOURCE_UNREADABLE) }
            return
        }
        var start = resumeOffsets[i]
        var expectDigest = resumeDigests[i]
        var integrityLeft = cfg.integrityRetries
        var chunkFailures = 0
        while (true) {
            val outcome = attempt(i, src, meta, start, expectDigest)
            expectDigest = null
            when (outcome) {
                is Outcome.Done -> {
                    val r = outcome.result
                    if (r.status == FileStatus.COMPLETED) {
                        setFile(i) { it.copy(status = FileStatus.COMPLETED, bytesDone = it.size, savedAs = r.savedAs, error = null) }
                        return
                    }
                    if (r.status == FileStatus.FAILED && r.error == ErrorCode.INTEGRITY_FAILED && integrityLeft-- > 0) {
                        Log.w(TAG, "Integrity failure on file ${i + 1}; re-sending from the start")
                        start = 0
                        continue
                    }
                    setFile(i) { it.copy(status = r.status, error = r.error) }
                    return
                }
                is Outcome.Rewind -> {
                    if (outcome.chunkFailure && ++chunkFailures > cfg.chunkRetries) {
                        io!!.conn.send(CancelFile(meta.fileId, ErrorCode.INTEGRITY_FAILED))
                        setFile(i) { it.copy(status = FileStatus.FAILED, error = ErrorCode.INTEGRITY_FAILED) }
                        return
                    }
                    start = outcome.offset
                }
                is Outcome.Cancelled -> {
                    setFile(i) { it.copy(status = FileStatus.CANCELLED, error = outcome.code) }
                    return
                }
                is Outcome.LocalFailure -> {
                    io!!.conn.send(CancelFile(meta.fileId, outcome.code))
                    setFile(i) { it.copy(status = FileStatus.FAILED, error = outcome.code) }
                    return
                }
            }
        }
    }

    private suspend fun attempt(i: Int, src: FileSource, meta: FileMeta, startOffset: Long, expectDigest: String?): Outcome {
        val io = this.io!!
        val fileId = meta.fileId
        // Chunk-list digest: SHA-256 over every chunk hash. Each byte is hashed exactly once.
        val fileDigest = Hashing.sha256()
        var offset = startOffset.coerceIn(0, meta.size)
        val buf = ByteArray(chunkSize)
        var acked = offset
        var sent = offset
        val handler: suspend (Ev) -> Outcome? = handler@{ ev ->
            when (ev) {
                is Ev.Msg -> when (val m = ev.m) {
                    is Ack -> {
                        if (m.fileId == fileId && m.offset > acked) acked = m.offset
                        null
                    }
                    is FileResult -> if (m.fileId == fileId) Outcome.Done(m) else null
                    is Blocked -> if (m.fileId == fileId) waitUnblocked(m) else null
                    is Resend -> if (m.fileId == fileId) Outcome.Rewind(m.offset, chunkFailure = true) else null
                    is CancelFile -> {
                        if (m.fileId == fileId) return@handler Outcome.Cancelled(ErrorCode.PEER_CANCELLED)
                        markCancelled(m.fileId, ErrorCode.PEER_CANCELLED)
                        null
                    }
                    else -> {
                        handleCommonMessage(m); null
                    }
                }
                is Ev.Cmd -> when (val c = ev.c) {
                    is SenderCommand.CancelFile -> {
                        io.conn.send(CancelFile(c.fileId, ErrorCode.USER_CANCELLED))
                        if (c.fileId == fileId) return@handler Outcome.Cancelled(ErrorCode.USER_CANCELLED)
                        markCancelled(c.fileId, ErrorCode.USER_CANCELLED)
                        null
                    }
                    else -> {
                        handleCommonCommand(c); null
                    }
                }
                Ev.Timeout -> null
            }
        }

        // Resuming: re-read our own prefix to rebuild the whole-file hash and to confirm
        // the receiver's partial data matches this file chunk for chunk.
        if (offset > 0) {
            try {
                src.open(0).use { input ->
                    var pos = 0L
                    while (pos < offset) {
                        val want = minOf(chunkSize.toLong(), offset - pos).toInt()
                        val n = readFully(input, buf, want)
                        if (n < want) return Outcome.LocalFailure(ErrorCode.SOURCE_CHANGED)
                        fileDigest.update(Hashing.sha256(buf, 0, n))
                        pos += n
                        pollAll { ev -> handler(ev)?.let { return it } }
                    }
                }
            } catch (e: IOException) {
                return Outcome.LocalFailure(IoErrorClassifier.classifyRead(e))
            } catch (e: SecurityException) {
                return Outcome.LocalFailure(ErrorCode.SOURCE_UNREADABLE)
            }
            val prefix = (fileDigest.clone() as java.security.MessageDigest).digest()
            if (expectDigest != null && Hex.encode(prefix) != expectDigest) {
                Log.w(TAG, "Receiver's partial data for file ${i + 1} doesn't match the source; restarting it")
                io.conn.send(RestartFile(fileId, ErrorCode.RESUME_INVALID))
                errorDetail = ErrorCode.RESUME_INVALID.userMessage
                offset = 0
                fileDigest.reset()
            }
        }
        if (sourceChanged(src, meta)) return Outcome.LocalFailure(ErrorCode.SOURCE_CHANGED)

        io.conn.send(FileStart(fileId, offset))
        acked = offset
        sent = offset
        setFile(i) { it.copy(status = FileStatus.TRANSFERRING, bytesDone = offset, error = null) }
        state = SessionState.TRANSFERRING
        storageIssue = null
        publish(persist = true)

        val input: InputStream = try {
            src.open(offset)
        } catch (e: IOException) {
            return Outcome.LocalFailure(IoErrorClassifier.classifyRead(e))
        } catch (e: SecurityException) {
            return Outcome.LocalFailure(ErrorCode.SOURCE_UNREADABLE)
        }
        input.use {
            while (sent < meta.size) {
                pollAll { ev -> handler(ev)?.let { return it } }
                while (pausedByUser || pausedByPeer || sent - acked >= cfg.maxInFlightBytes) {
                    if (pausedByUser || pausedByPeer) {
                        state = SessionState.PAUSED; publish(persist = true)
                    }
                    handler(nextEvent(1_000))?.let { return it }
                    if (state == SessionState.PAUSED && !pausedByUser && !pausedByPeer) {
                        state = SessionState.TRANSFERRING; publish(persist = true)
                    }
                }
                val want = minOf(chunkSize.toLong(), meta.size - sent).toInt()
                val n = try {
                    readFully(input, buf, want)
                } catch (e: IOException) {
                    return Outcome.LocalFailure(IoErrorClassifier.classifyRead(e))
                } catch (e: SecurityException) {
                    return Outcome.LocalFailure(ErrorCode.SOURCE_UNREADABLE)
                }
                if (n < want) return Outcome.LocalFailure(ErrorCode.SOURCE_CHANGED)
                val chunkHash = Hashing.sha256(buf, 0, n)
                fileDigest.update(chunkHash)
                sendData(io, i, sent, chunkHash, buf, n)
                sent += n
                bytesOnWire += n
                speed.record(bytesOnWire)
                setFile(i) { it.copy(bytesDone = sent) }
                publish()
            }
            val extra = try { input.read() } catch (e: IOException) { -1 }
            if (extra != -1) return Outcome.LocalFailure(ErrorCode.SOURCE_CHANGED)
        }
        if (sourceChanged(src, meta)) return Outcome.LocalFailure(ErrorCode.SOURCE_CHANGED)

        io.conn.send(FileEnd(fileId, meta.size, Hex.encode(fileDigest.digest())))
        setFile(i) { it.copy(status = FileStatus.VERIFYING, bytesDone = meta.size) }
        publish(persist = true)
        while (true) {
            handler(nextEvent(5_000))?.let { return it }
        }
    }

    /** The receiver can't write right now. Wait (connection open) until it says to continue. */
    private suspend fun waitUnblocked(b: Blocked): Outcome {
        state = SessionState.BLOCKED
        storageIssue = StorageIssue(b.reason, b.fileId, b.requiredBytes, b.availableBytes, peer.name)
        error = b.reason
        val idx = metas.indexOfFirst { it.fileId == b.fileId }
        if (idx >= 0) setFile(idx) { it.copy(status = FileStatus.BLOCKED, bytesDone = b.verifiedOffset, error = b.reason) }
        publish(persist = true)
        while (true) {
            when (val ev = nextEvent(5_000)) {
                is Ev.Msg -> when (val m = ev.m) {
                    is Unblocked -> if (m.fileId == b.fileId) {
                        state = SessionState.TRANSFERRING
                        storageIssue = null
                        error = null
                        if (idx >= 0) setFile(idx) { it.copy(status = FileStatus.TRANSFERRING, error = null) }
                        publish(persist = true)
                        return Outcome.Rewind(m.offset, chunkFailure = false)
                    }
                    is Blocked -> if (m.fileId == b.fileId) {
                        storageIssue = StorageIssue(m.reason, m.fileId, m.requiredBytes, m.availableBytes, peer.name)
                        publish(persist = true)
                    }
                    is CancelFile -> if (m.fileId == b.fileId) return Outcome.Cancelled(ErrorCode.PEER_CANCELLED) else markCancelled(m.fileId, ErrorCode.PEER_CANCELLED)
                    is Ack, is FileResult -> {}
                    else -> handleCommonMessage(m)
                }
                is Ev.Cmd -> when (val c = ev.c) {
                    is SenderCommand.CancelFile -> {
                        io!!.conn.send(CancelFile(c.fileId, ErrorCode.USER_CANCELLED))
                        if (c.fileId == b.fileId) return Outcome.Cancelled(ErrorCode.USER_CANCELLED)
                        markCancelled(c.fileId, ErrorCode.USER_CANCELLED)
                    }
                    else -> handleCommonCommand(c)
                }
                Ev.Timeout -> {}
            }
        }
    }

    private fun sendData(io: SessionIo, i: Int, offset: Long, hash: ByteArray, buf: ByteArray, n: Int) {
        try {
            io.conn.sendData(i, offset, hash, buf, n)
        } catch (e: IOException) {
            throw io.networkError(e)
        }
    }

    private fun sourceChanged(src: FileSource, meta: FileMeta): Boolean {
        val stat = src.currentStat() ?: return false
        if (stat.first != meta.size) return true
        val m = meta.modifiedAt
        val now = stat.second
        return m != null && now != null && m != now
    }

    private fun handleCommonMessage(m: Message) {
        when (m) {
            is CancelSession -> throw SessionCancelled(ErrorCode.PEER_CANCELLED)
            is ProtocolErrorMessage -> throw TransferException(m.code, m.detail.ifEmpty { m.code.userMessage })
            is Pause -> if (m.byReceiver) {
                pausedByPeer = true; publish(persist = true)
            }
            is ResumeTransfer -> if (m.byReceiver) {
                pausedByPeer = false; publish(persist = true)
            }
            else -> {} // late acks/results for earlier files etc.
        }
    }

    private fun handleCommonCommand(c: SenderCommand) {
        when (c) {
            SenderCommand.Cancel -> {
                if (accepted || state == SessionState.AWAITING_APPROVAL) {
                    runCatching { io?.conn?.send(CancelSession(ErrorCode.USER_CANCELLED)) }
                }
                throw SessionCancelled(ErrorCode.USER_CANCELLED)
            }
            SenderCommand.Pause -> if (!pausedByUser) {
                pausedByUser = true
                runCatching { io?.conn?.send(Pause(byReceiver = false)) }
                state = SessionState.PAUSED
                publish(persist = true)
            }
            SenderCommand.Resume -> if (pausedByUser) {
                pausedByUser = false
                runCatching { io?.conn?.send(ResumeTransfer(byReceiver = false)) }
                publish(persist = true)
            }
            is SenderCommand.CancelFile -> {
                if (accepted) runCatching { io?.conn?.send(CancelFile(c.fileId, ErrorCode.USER_CANCELLED)) }
                markCancelled(c.fileId, ErrorCode.USER_CANCELLED)
            }
        }
    }

    private fun markCancelled(fileId: String, code: ErrorCode) {
        val idx = metas.indexOfFirst { it.fileId == fileId }
        if (idx >= 0 && !files[idx].status.terminal) setFile(idx) { it.copy(status = FileStatus.CANCELLED, error = code) }
        publish(persist = true)
    }

    private suspend inline fun <reified T : Message> awaitMessage(timeoutMs: Long): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) {
                throw TransferException(if (T::class == OfferResponse::class) ErrorCode.APPROVAL_TIMEOUT else ErrorCode.TIMEOUT)
            }
            when (val ev = nextEvent(minOf(left, 1_000))) {
                is Ev.Msg -> if (ev.m is T) return ev.m as T else handleCommonMessage(ev.m)
                is Ev.Cmd -> handleCommonCommand(ev.c)
                Ev.Timeout -> {}
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun nextEvent(timeoutMs: Long): Ev {
        val io = this.io!!
        commands.tryReceive().getOrNull()?.let { return Ev.Cmd(it) } // user commands take priority
        return select {
            io.frames.onReceiveCatching { r ->
                val f = r.getOrNull() ?: throw io.networkError(r.exceptionOrNull())
                when (f) {
                    is Frame.Control -> Ev.Msg(f.message)
                    is Frame.Data -> throw TransferException(ErrorCode.PROTOCOL_ERROR, "receiver sent data")
                }
            }
            commands.onReceive { Ev.Cmd(it) }
            onTimeout(timeoutMs) { Ev.Timeout }
        }
    }

    /** Handles everything already queued without blocking. */
    private suspend inline fun pollAll(onEvent: (Ev) -> Unit) {
        val io = this.io!!
        while (true) {
            val r = io.frames.tryReceive()
            if (r.isClosed) throw io.networkError(r.exceptionOrNull())
            val f = r.getOrNull()
            if (f != null) {
                when (f) {
                    is Frame.Control -> onEvent(Ev.Msg(f.message))
                    is Frame.Data -> throw TransferException(ErrorCode.PROTOCOL_ERROR)
                }
                continue
            }
            val c = commands.tryReceive().getOrNull() ?: return
            onEvent(Ev.Cmd(c))
        }
    }

    /** Waits [ms]; returns true if the user cancelled meanwhile. Other commands are applied. */
    private suspend fun waitOrCancel(ms: Long): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return false
            val c = withTimeoutOrNull(left) { commands.receive() } ?: return false
            when (c) {
                SenderCommand.Cancel -> return true
                SenderCommand.Pause -> pausedByUser = true
                SenderCommand.Resume -> pausedByUser = false
                is SenderCommand.CancelFile -> markCancelled(c.fileId, ErrorCode.USER_CANCELLED)
            }
        }
    }

    private fun markActiveFiles(status: FileStatus, code: ErrorCode? = null) = synchronized(this) {
        for (i in files.indices) {
            val f = files[i]
            if (!f.status.terminal) files[i] = f.copy(status = status, error = code ?: f.error)
        }
    }

    private fun closeIo() {
        io?.close()
        io = null
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    companion object {
        private const val TAG = "Sender"

        fun finalState(files: List<FileProgress>): SessionState = when {
            files.all { it.status == FileStatus.COMPLETED } -> SessionState.COMPLETED
            files.any { !it.status.terminal } -> SessionState.INTERRUPTED
            files.none { it.status == FileStatus.COMPLETED } && files.all { it.status == FileStatus.CANCELLED } -> SessionState.CANCELLED
            files.none { it.status == FileStatus.COMPLETED || it.status == FileStatus.SKIPPED } -> SessionState.FAILED
            else -> SessionState.PARTIAL
        }
    }
}
