package com.transferx.core.transfer

import com.transferx.core.protocol.Ack
import com.transferx.core.protocol.Blocked
import com.transferx.core.protocol.CancelFile
import com.transferx.core.protocol.CancelSession
import com.transferx.core.protocol.ErrorCode
import com.transferx.core.protocol.FileEnd
import com.transferx.core.protocol.FileResult
import com.transferx.core.protocol.FileResumeInfo
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
import com.transferx.core.storage.FileNameSanitizer
import com.transferx.core.storage.IoErrorClassifier
import com.transferx.core.storage.PlannedFile
import com.transferx.core.storage.PreflightResult
import com.transferx.core.storage.StoragePreflight
import com.transferx.core.util.Hashing
import com.transferx.core.util.Ids
import com.transferx.core.util.Log
import com.transferx.core.util.SpeedMeter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

sealed class ReceiverCommand {
    data object Cancel : ReceiverCommand()
    data class CancelFile(val fileId: String) : ReceiverCommand()
    data object Pause : ReceiverCommand()
    data object Resume : ReceiverCommand()

    /** Re-check storage (after the user freed space), optionally moving to [newDestination]. */
    data class RetryStorage(val newDestination: Path?) : ReceiverCommand()
}

/**
 * Receiving side of one session. Owns the partial files, checkpoints, final
 * verification, and atomic finalization. Never reports a file as completed until it
 * has been re-read from disk, matched the sender's SHA-256, and been moved into place.
 */
internal class ReceiverSession(
    private val engine: TransferEngine,
    private val io: SessionIo,
    private val peer: PeerInfo,
    private val transport: TransportKind,
) {
    private val cfg = engine.config
    private val fs = engine.storage
    val commands = Channel<ReceiverCommand>(Channel.UNLIMITED)

    lateinit var id: String
        private set
    private lateinit var manifest: ReceiveManifest
    private lateinit var destination: Path
    private lateinit var partialDir: Path
    private lateinit var preflight: StoragePreflight
    private var offer: Offer? = null
    private var createdAt = System.currentTimeMillis()

    @Volatile private var state = SessionState.AWAITING_APPROVAL
    private var error: ErrorCode? = null
    private var errorDetail: String? = null
    private var storageIssue: StorageIssue? = null
    private var pausedByUser = false
    private var pausedByPeer = false
    private var bytesDone = LongArray(0)
    private val partials = HashMap<String, PartialFile>()
    private val validated = HashSet<String>()
    private var current = -1
    private var discard = -1
    private var blockedFile = -1
    private val speed = SpeedMeter()
    private var received = 0L
    private var lastPublish = 0L
    private var registered = false

    private sealed class Ev {
        class Data(val f: Frame.Data) : Ev()
        class Msg(val m: Message) : Ev()
        class Cmd(val c: ReceiverCommand) : Ev()
        data object Timeout : Ev()
    }

    // ---------------------------------------------------------------- snapshots

    fun snapshot(): SessionSnapshot {
        val files = if (::manifest.isInitialized) manifest.files.map { e ->
            FileProgress(
                fileId = e.fileId, name = e.name, relativeDir = e.relativeDir, size = e.size, mimeType = e.mimeType,
                bytesDone = if (e.status == FileStatus.COMPLETED) e.size else bytesDone.getOrElse(e.index) { 0 },
                status = e.status, error = e.error,
                savedAs = e.finalPath?.takeIf { e.status == FileStatus.COMPLETED }?.let { relativeToDest(Paths.get(it)) },
            )
        } else emptyList()
        val remaining = files.filter { !it.status.terminal }.sumOf { (it.size - it.bytesDone).coerceAtLeast(0) }
        val live = state == SessionState.TRANSFERRING
        return SessionSnapshot(
            id = if (::id.isInitialized) id else "", direction = Direction.RECEIVE, transport = transport, peer = peer,
            state = state, files = files, createdAt = createdAt, updatedAt = System.currentTimeMillis(),
            bytesPerSecond = if (live) speed.bytesPerSecond() else null,
            etaSeconds = if (live) speed.etaSeconds(remaining) else null,
            error = error, errorDetail = errorDetail,
            destination = if (::destination.isInitialized) destination.toString() else null,
            storageIssue = storageIssue, pausedByPeer = pausedByPeer,
        )
    }

    private fun publish(persist: Boolean = false) {
        if (!::id.isInitialized || !::manifest.isInitialized) return
        val now = System.nanoTime()
        if (!persist && now - lastPublish < 100_000_000L) return
        lastPublish = now
        engine.publish(TransferRecord(snapshot()), persist)
    }

    private fun relativeToDest(p: Path): String = try {
        destination.toAbsolutePath().relativize(p.toAbsolutePath()).toString().replace('\\', '/')
    } catch (e: Exception) {
        p.fileName.toString()
    }

    // ---------------------------------------------------------------- offer

    suspend fun handleOffer(offer: Offer) {
        this.offer = offer
        if (!Ids.isValid(offer.sessionId)) throw TransferException(ErrorCode.PROTOCOL_ERROR, "invalid session id")
        id = offer.sessionId
        validateOffer(offer)
        if (engine.isReceiveSessionKnown(id)) {
            io.conn.send(ProtocolErrorMessage(ErrorCode.PROTOCOL_ERROR, "duplicate session id"))
            return
        }
        val settings = engine.receiveSettings()
        if (!settings.accepting) {
            io.conn.send(OfferResponse(id, false, reason = ErrorCode.NOT_ACCEPTING))
            return
        }
        if (!engine.registerReceiver(this)) {
            io.conn.send(OfferResponse(id, false, reason = ErrorCode.BUSY))
            return
        }
        registered = true
        try {
            destination = settings.destination
            preflight = StoragePreflight(fs, cfg.storageReserveBytes, offer.chunkSize)
            bytesDone = LongArray(offer.files.size)
            manifest = ReceiveManifest(
                sessionId = id, peer = peer, transport = transport, chunkSize = offer.chunkSize,
                destination = destination.toString(), conflictPolicy = settings.conflictPolicy,
                createdAt = createdAt, updatedAt = createdAt, state = SessionState.AWAITING_APPROVAL,
                files = offer.files.mapIndexed { i, f -> entryFor(i, f) },
            )
            state = SessionState.AWAITING_APPROVAL
            publish(persist = true)
            val decision = awaitDecision() ?: return
            if (!acceptWith(decision)) return
            transferLoop()
        } finally {
            closePartials()
            engine.unregisterReceiver(this)
            publish(persist = true)
        }
    }

    private fun validateOffer(offer: Offer) {
        if (offer.chunkSize !in EngineConfig.MIN_CHUNK..EngineConfig.MAX_CHUNK) throw TransferException(ErrorCode.PROTOCOL_ERROR, "chunk size")
        if (offer.files.isEmpty() || offer.files.size > cfg.maxFilesPerOffer) throw TransferException(ErrorCode.PROTOCOL_ERROR, "file count")
        val ids = HashSet<String>()
        for (f in offer.files) {
            if (!Ids.isValid(f.fileId) || !ids.add(f.fileId)) throw TransferException(ErrorCode.PROTOCOL_ERROR, "file id")
            if (f.size < 0) throw TransferException(ErrorCode.PROTOCOL_ERROR, "file size")
        }
    }

    private fun entryFor(index: Int, f: com.transferx.core.protocol.FileMeta): ReceiveFileEntry {
        val dir = FileNameSanitizer.sanitizeRelativeDir(f.relativeDir)
        val rawName = f.name.trim()
        val unsafeName = rawName.isEmpty() || rawName == "." || rawName == ".." || rawName.contains('/') ||
            rawName.contains('\\') || rawName.contains('\u0000')
        val name = FileNameSanitizer.sanitizeFileName(f.name)
        val unsafe = dir == null || unsafeName
        if (unsafe) Log.w(TAG, "Refusing file ${index + 1} from ${peer.name}: unsafe name or path")
        return ReceiveFileEntry(
            fileId = f.fileId, index = index, name = name, relativeDir = dir?.joinToString("/") ?: "",
            size = f.size, mimeType = f.mimeType?.take(128), modifiedAt = f.modifiedAt,
            accepted = false,
            status = if (unsafe) FileStatus.SKIPPED else FileStatus.PENDING,
            error = if (unsafe) ErrorCode.UNSAFE_NAME else null,
        )
    }

    /** Builds the Incoming Transfer prompt, including storage preflight for [dest]. */
    fun buildOfferPrompt(promptId: String, dest: Path, expiresAt: Long): Prompt.IncomingOffer {
        val candidates = manifest.files.filter { it.status != FileStatus.SKIPPED }
        val pre = preflight.check(dest, candidates.map { PlannedFile(it.fileId, it.size) }, engine.committedBytes(dest, id))
        val files = manifest.files.map { e ->
            val target = runCatching { FileNameSanitizer.resolveInside(dest, e.relativeDir.split('/').filter { it.isNotEmpty() }, e.name) }.getOrNull()
            OfferedFile(
                fileId = e.fileId, name = e.name, relativeDir = e.relativeDir, size = e.size, mimeType = e.mimeType,
                conflictsWithExisting = target != null && fs.exists(target),
                fits = e.fileId in pre.fittingFileIds,
                tooLargeForFilesystem = e.fileId in pre.tooLargeForFilesystem,
                unsafe = e.error == ErrorCode.UNSAFE_NAME,
            )
        }
        return Prompt.IncomingOffer(promptId, id, peer, transport, files, dest.toString(), pre, expiresAt)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitDecision(): OfferDecision? {
        val promptId = Ids.newId()
        val deferred = CompletableDeferred<OfferDecision>()
        val t0 = System.nanoTime()
        val prompt = buildOfferPrompt(promptId, destination, 0)
        // The approval window starts when the user can actually see the request.
        val deadline = System.currentTimeMillis() + cfg.approvalTimeoutMs
        engine.registerOfferPrompt(promptId, this, deferred)
        engine.addPrompt(prompt.copy(expiresAt = deadline))
        Log.i(TAG, "Offer ${id.take(6)} from ${peer.name}: ${prompt.files.size} file(s), preflight ${(System.nanoTime() - t0) / 1_000_000} ms")
        try {
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) {
                    io.conn.send(OfferResponse(id, false, reason = ErrorCode.APPROVAL_TIMEOUT))
                    endWithoutTransfer(SessionState.REJECTED, ErrorCode.APPROVAL_TIMEOUT)
                    return null
                }
                val outcome: Any? = select {
                    deferred.onAwait { it }
                    io.frames.onReceiveCatching { r ->
                        val f = r.getOrNull() ?: return@onReceiveCatching io.networkError(r.exceptionOrNull())
                        (f as? Frame.Control)?.message
                    }
                    commands.onReceive { it }
                    onTimeout(left) { null }
                }
                when (outcome) {
                    is OfferDecision -> return outcome
                    is TransferException -> {
                        endWithoutTransfer(SessionState.FAILED, outcome.code); return null
                    }
                    is CancelSession -> {
                        // Sender cancelled while we were deciding.
                        endWithoutTransfer(SessionState.CANCELLED, ErrorCode.PEER_CANCELLED); return null
                    }
                    is ReceiverCommand.Cancel -> {
                        io.conn.send(OfferResponse(id, false, reason = ErrorCode.REJECTED))
                        endWithoutTransfer(SessionState.REJECTED, ErrorCode.REJECTED); return null
                    }
                    else -> {}
                }
            }
        } finally {
            engine.removePrompt(promptId)
            engine.unregisterOfferPrompt(promptId)
        }
    }

    private fun endWithoutTransfer(s: SessionState, code: ErrorCode) {
        state = s
        error = code
        manifest = manifest.copy(
            state = s,
            files = manifest.files.map { if (it.status.terminal) it else it.copy(status = FileStatus.SKIPPED, error = code) },
        )
    }

    private fun acceptWith(decision: OfferDecision): Boolean {
        if (!decision.accept) {
            io.conn.send(OfferResponse(id, false, reason = ErrorCode.REJECTED))
            endWithoutTransfer(SessionState.REJECTED, ErrorCode.REJECTED)
            return false
        }
        val dest = decision.destination ?: destination
        val safe = manifest.files.filter { it.status != FileStatus.SKIPPED }
        val pre: PreflightResult = preflight.check(dest, safe.map { PlannedFile(it.fileId, it.size) }, engine.committedBytes(dest, id))
        if (!pre.writable) {
            io.conn.send(OfferResponse(id, false, reason = ErrorCode.PERMISSION_DENIED))
            endWithoutTransfer(SessionState.FAILED, ErrorCode.PERMISSION_DENIED)
            return false
        }
        val requested = decision.acceptedFileIds ?: pre.fittingFileIds.toSet()
        val acceptedIds = requested.filter { fid -> safe.any { it.fileId == fid } && fid !in pre.tooLargeForFilesystem }.toSet()
        if (acceptedIds.isEmpty()) {
            val reason = if (pre.nonFittingFileIds.isNotEmpty()) ErrorCode.INSUFFICIENT_STORAGE else ErrorCode.REJECTED
            io.conn.send(OfferResponse(id, false, reason = reason))
            endWithoutTransfer(SessionState.REJECTED, reason)
            return false
        }
        destination = dest
        partialDir = engine.manifests.partialDir(dest, id)
        fs.createDirectories(partialDir)
        manifest = manifest.copy(
            destination = dest.toString(),
            conflictPolicy = decision.conflictPolicy,
            state = SessionState.TRANSFERRING,
            files = manifest.files.map { e ->
                when {
                    e.status == FileStatus.SKIPPED -> e
                    e.fileId in acceptedIds -> e.copy(accepted = true)
                    e.fileId in pre.tooLargeForFilesystem -> e.copy(status = FileStatus.SKIPPED, error = ErrorCode.FILE_TOO_LARGE_FOR_FILESYSTEM)
                    else -> e.copy(status = FileStatus.SKIPPED, error = ErrorCode.NOT_SELECTED)
                }
            },
        )
        saveManifest()
        engine.receiveIndex.put(id, partialDir)
        io.conn.send(OfferResponse(id, true, acceptedIds.toList()))
        state = SessionState.TRANSFERRING
        publish(persist = true)
        Log.i(TAG, "Accepted ${acceptedIds.size} file(s) from ${peer.name} into $dest")
        return true
    }

    // ---------------------------------------------------------------- resume

    suspend fun handleResume(req: ResumeRequest) {
        if (!Ids.isValid(req.sessionId)) throw TransferException(ErrorCode.PROTOCOL_ERROR)
        id = req.sessionId
        if (!engine.takeOverStaleReceiver(id, peer.fingerprint)) {
            io.conn.send(ResumeState(id, false, error = ErrorCode.BUSY))
            return
        }
        val dir = engine.receiveIndex.get(id)
        val m = dir?.let { engine.manifests.load(it) }
        if (dir == null || m == null) {
            io.conn.send(ResumeState(id, false, error = ErrorCode.SESSION_NOT_FOUND))
            return
        }
        if (m.peer.fingerprint != peer.fingerprint) {
            Log.w(TAG, "Resume of ${id.take(6)} refused: requested by a different device")
            io.conn.send(ResumeState(id, false, error = ErrorCode.UNAUTHORIZED))
            return
        }
        if (!engine.registerReceiver(this)) {
            io.conn.send(ResumeState(id, false, error = ErrorCode.BUSY))
            return
        }
        registered = true
        try {
            manifest = m.copy(peer = m.peer.copy(name = peer.name, address = peer.address))
            partialDir = dir
            destination = Paths.get(m.destination)
            createdAt = m.createdAt
            preflight = StoragePreflight(fs, cfg.storageReserveBytes, m.chunkSize)
            bytesDone = LongArray(m.files.size)
            recoverInterruptedFinalization()
            val infos = mutableListOf<FileResumeInfo>()
            for (e in manifest.files) {
                if (!e.accepted) continue
                when {
                    e.status == FileStatus.COMPLETED -> infos += FileResumeInfo(e.fileId, FileStatus.COMPLETED, e.size)
                    e.status.terminal && !(e.status == FileStatus.FAILED && e.error == ErrorCode.INTEGRITY_FAILED) ->
                        infos += FileResumeInfo(e.fileId, e.status, 0, error = e.error)
                    else -> {
                        val pf = partial(e)
                        val v = try {
                            pf.loadAndValidate()
                        } catch (ex: IOException) {
                            pf.resetFiles(); PartialFile.Validation(0, PartialFile.emptyDigest(), true, "partial unreadable")
                        }
                        validated += e.fileId
                        if (v.reason != null) Log.w(TAG, "File ${e.index + 1}: ${v.reason}; resuming from ${v.verifiedOffset}")
                        bytesDone[e.index] = v.verifiedOffset
                        setEntry(e.index) { it.copy(status = FileStatus.PENDING, error = null) }
                        infos += FileResumeInfo(e.fileId, FileStatus.INTERRUPTED, v.verifiedOffset, v.prefixDigest)
                    }
                }
            }
            manifest = manifest.copy(state = SessionState.TRANSFERRING)
            saveManifestQuiet()
            io.conn.send(ResumeState(id, true, infos))
            state = SessionState.TRANSFERRING
            publish(persist = true)
            Log.i(TAG, "Resumed session ${id.take(6)} from ${peer.name}")
            transferLoop()
        } finally {
            closePartials()
            engine.unregisterReceiver(this)
            publish(persist = true)
        }
    }

    /**
     * A crash between "move .part into place" and "mark completed" leaves a final file
     * without a completed status. If the final file exists and matches the recorded
     * SHA-256, it's complete. Otherwise the file restarts.
     */
    private fun recoverInterruptedFinalization() {
        for (e in manifest.files) {
            if (e.status == FileStatus.COMPLETED || e.finalPath == null || e.expectedDigest == null) continue
            val pf = partial(e)
            if (fs.exists(pf.partPath)) continue // move never happened; normal resume path
            val target = Paths.get(e.finalPath)
            val ok = fs.exists(target) && fs.size(target) == e.size && runCatching { fs.openReader(target).use { com.transferx.core.util.FileDigest.of(it, manifest.chunkSize) } }.getOrNull() == e.expectedDigest
            setEntry(e.index) {
                if (ok) it.copy(status = FileStatus.COMPLETED, error = null)
                else it.copy(status = FileStatus.PENDING, finalPath = null, expectedDigest = null)
            }
            if (ok) pf.deleteMetadata()
        }
    }

    // ---------------------------------------------------------------- transfer loop

    private suspend fun transferLoop() {
        try {
            while (true) {
                val over = when (val ev = nextEvent()) {
                    is Ev.Data -> {
                        onData(ev.f); false
                    }
                    is Ev.Msg -> onMessage(ev.m)
                    is Ev.Cmd -> onCommand(ev.c)
                    Ev.Timeout -> false
                }
                if (over) return
            }
        } catch (e: TransferException) {
            interrupted(e.code)
        } catch (e: IOException) {
            interrupted(io.networkError(e).code)
        }
    }

    private fun interrupted(code: ErrorCode) {
        // Data written so far was verified chunk by chunk; make it durable so less is lost.
        if (current >= 0) runCatching { partials[manifest.files[current].fileId]?.checkpoint() }
        for (i in manifest.files.indices) {
            val e = manifest.files[i]
            if (e.accepted && !e.status.terminal) {
                val off = partials[e.fileId]?.verifiedOffset ?: bytesDone[i]
                bytesDone[i] = off
                setEntry(i) { it.copy(status = FileStatus.INTERRUPTED, error = code) }
            }
        }
        state = SessionState.INTERRUPTED
        error = code
        manifest = manifest.copy(state = SessionState.INTERRUPTED)
        saveManifestQuiet()
        Log.w(TAG, "Session ${id.take(6)} interrupted: $code")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun nextEvent(): Ev {
        // User commands first: during a transfer the frame channel is never empty, and
        // select is biased, so commands would otherwise wait until the stream drains.
        commands.tryReceive().getOrNull()?.let { return Ev.Cmd(it) }
        return selectEvent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun selectEvent(): Ev = select {
        io.frames.onReceiveCatching { r ->
            val f = r.getOrNull() ?: throw io.networkError(r.exceptionOrNull())
            when (f) {
                is Frame.Data -> Ev.Data(f)
                is Frame.Control -> Ev.Msg(f.message)
            }
        }
        commands.onReceive { Ev.Cmd(it) }
        onTimeout(5_000) { Ev.Timeout }
    }

    private fun onMessage(m: Message): Boolean {
        when (m) {
            is FileStart -> onFileStart(m)
            is FileEnd -> onFileEnd(m)
            is CancelFile -> onCancelFile(m.fileId, if (m.reason == ErrorCode.USER_CANCELLED) ErrorCode.PEER_CANCELLED else m.reason)
            is CancelSession -> {
                cancelAll(ErrorCode.PEER_CANCELLED); return true
            }
            is Pause -> if (!m.byReceiver) {
                pausedByPeer = true
                if (state == SessionState.TRANSFERRING) state = SessionState.PAUSED
                publish(persist = true)
            }
            is ResumeTransfer -> if (!m.byReceiver) {
                pausedByPeer = false
                if (state == SessionState.PAUSED && !pausedByUser) state = SessionState.TRANSFERRING
                publish(persist = true)
            }
            is RestartFile -> {
                val idx = indexOf(m.fileId)
                partials[m.fileId]?.resetFiles()
                bytesDone[idx] = 0
                errorDetail = ErrorCode.RESUME_INVALID.userMessage
                Log.w(TAG, "Sender requested restart of file ${idx + 1}: ${m.reason}")
            }
            is SessionEnd -> {
                finish()
                runCatching { io.conn.send(SessionEnd(id)) }
                return true
            }
            is ProtocolErrorMessage -> throw TransferException(m.code, m.detail)
            else -> {}
        }
        return false
    }

    private fun onCommand(c: ReceiverCommand): Boolean {
        when (c) {
            ReceiverCommand.Cancel -> {
                runCatching { io.conn.send(CancelSession(ErrorCode.USER_CANCELLED)) }
                cancelAll(ErrorCode.USER_CANCELLED)
                return true
            }
            is ReceiverCommand.CancelFile -> {
                runCatching { io.conn.send(CancelFile(c.fileId, ErrorCode.USER_CANCELLED)) }
                onCancelFile(c.fileId, ErrorCode.USER_CANCELLED)
            }
            ReceiverCommand.Pause -> if (!pausedByUser) {
                pausedByUser = true
                io.conn.send(Pause(byReceiver = true))
                if (state == SessionState.TRANSFERRING) state = SessionState.PAUSED
                publish(persist = true)
            }
            ReceiverCommand.Resume -> if (pausedByUser) {
                pausedByUser = false
                io.conn.send(ResumeTransfer(byReceiver = true))
                if (state == SessionState.PAUSED && !pausedByPeer) state = SessionState.TRANSFERRING
                publish(persist = true)
            }
            is ReceiverCommand.RetryStorage -> retryStorage(c.newDestination)
        }
        return false
    }

    private fun onFileStart(m: FileStart) {
        val idx = indexOf(m.fileId)
        val e = manifest.files[idx]
        if (!e.accepted) throw TransferException(ErrorCode.UNAUTHORIZED, "file was not accepted")
        val restartable = e.status == FileStatus.FAILED && e.error == ErrorCode.INTEGRITY_FAILED
        if (e.status.terminal && !restartable) {
            // Late start for a file we already cancelled/finished: tell the sender again.
            io.conn.send(CancelFile(e.fileId, e.error ?: ErrorCode.USER_CANCELLED))
            discard = idx
            return
        }
        if (current >= 0 && current != idx) partials[manifest.files[current].fileId]?.close()
        current = idx
        val pf = partial(e)
        try {
            if (m.offset == 0L) {
                pf.resetFiles()
                validated += e.fileId
            } else {
                if (e.fileId !in validated) {
                    pf.loadAndValidate(); validated += e.fileId
                }
                if (m.offset != pf.writtenOffset) {
                    io.conn.send(Resend(e.fileId, pf.verifiedOffset))
                    current = -1
                    discard = idx
                    return
                }
            }
        } catch (ex: IOException) {
            onWriteFailure(ex); return
        } catch (ex: SecurityException) {
            onWriteFailure(ex); return
        }
        val need = e.size - m.offset + preflight.overheadFor(e.size)
        val (room, available) = preflight.hasRoomFor(destination, need)
        if (!room) {
            block(idx, ErrorCode.INSUFFICIENT_STORAGE, need, available)
            return
        }
        discard = -1
        bytesDone[idx] = m.offset
        setEntry(idx) { it.copy(status = FileStatus.TRANSFERRING, error = null) }
        if (state != SessionState.PAUSED) state = SessionState.TRANSFERRING
        storageIssue = null
        error = null
        saveManifestQuiet()
        publish(persist = true)
    }

    private fun onData(f: Frame.Data) {
        if (f.fileIndex == discard) return
        if (f.fileIndex != current) {
            if (f.fileIndex in manifest.files.indices && manifest.files[f.fileIndex].status.terminal) return
            throw TransferException(ErrorCode.PROTOCOL_ERROR, "data for a file that was not started")
        }
        val e = manifest.files[current]
        val pf = partial(e)
        if (f.offset < pf.writtenOffset) return // duplicate chunk: already have it
        val chunk = manifest.chunkSize
        val malformed = f.offset > pf.writtenOffset || f.offset % chunk != 0L || f.length > chunk || f.length == 0 ||
            f.offset + f.length > e.size || (f.length < chunk && f.offset + f.length != e.size)
        val corrupt = !malformed && !Hashing.constantTimeEquals(Hashing.sha256(f.data, 0, f.length), f.chunkHash)
        if (malformed || corrupt) {
            Log.w(TAG, "File ${current + 1}: ${if (corrupt) "chunk hash mismatch" else "unexpected chunk"} at ${f.offset}; re-requesting")
            pf.rollbackToCheckpoint()
            bytesDone[current] = pf.verifiedOffset
            io.conn.send(Resend(e.fileId, pf.verifiedOffset))
            discard = current
            return
        }
        try {
            pf.writeChunk(f.offset, f.data, f.length, f.chunkHash)
            if (pf.writtenOffset - pf.verifiedOffset >= cfg.checkpointIntervalBytes) {
                pf.checkpoint()
                io.conn.send(Ack(e.fileId, pf.verifiedOffset))
            }
        } catch (ex: IOException) {
            onWriteFailure(ex); return
        } catch (ex: SecurityException) {
            onWriteFailure(ex); return
        }
        bytesDone[current] = pf.writtenOffset
        received += f.length
        speed.record(received)
        publish()
    }

    private fun onFileEnd(m: FileEnd) {
        val idx = indexOf(m.fileId)
        if (idx == discard) return
        if (idx != current) throw TransferException(ErrorCode.PROTOCOL_ERROR, "file_end for inactive file")
        val e = manifest.files[idx]
        val pf = partial(e)
        if (pf.writtenOffset != e.size || m.size != e.size) {
            pf.rollbackToCheckpoint()
            io.conn.send(Resend(e.fileId, pf.verifiedOffset))
            discard = idx
            return
        }
        try {
            pf.checkpoint()
        } catch (ex: IOException) {
            onWriteFailure(ex); return
        } catch (ex: SecurityException) {
            onWriteFailure(ex); return
        }
        io.conn.send(Ack(e.fileId, e.size))
        setEntry(idx) { it.copy(status = FileStatus.VERIFYING, expectedDigest = m.digest.lowercase()) }
        saveManifestQuiet()
        publish(persist = true)

        val actual = try {
            pf.digestOnDisk()
        } catch (ex: IOException) {
            onWriteFailure(ex); return
        } catch (ex: SecurityException) {
            onWriteFailure(ex); return
        }
        if (!actual.equals(m.digest, ignoreCase = true)) {
            Log.w(TAG, "File ${idx + 1} failed integrity verification; discarding partial data")
            pf.deleteAll()
            partials.remove(e.fileId)
            validated -= e.fileId
            bytesDone[idx] = 0
            current = -1
            setEntry(idx) { it.copy(status = FileStatus.FAILED, error = ErrorCode.INTEGRITY_FAILED, expectedDigest = null) }
            saveManifestQuiet()
            io.conn.send(FileResult(e.fileId, FileStatus.FAILED, ErrorCode.INTEGRITY_FAILED))
            publish(persist = true)
            return
        }
        finalizeFile(idx, pf)
    }

    private fun finalizeFile(idx: Int, pf: PartialFile) {
        val e = manifest.files[idx]
        val segments = e.relativeDir.split('/').filter { it.isNotEmpty() }
        val target: Path
        try {
            synchronized(engine.finalizeLock) {
                val wanted = FileNameSanitizer.resolveInside(destination, segments, e.name)
                ensureNoSymlinks(destination, wanted.parent)
                fs.createDirectories(wanted.parent)
                var t = wanted
                var replace = false
                if (fs.exists(wanted)) {
                    when (manifest.conflictPolicy) {
                        ConflictPolicy.SKIP -> {
                            pf.deleteAll()
                            partials.remove(e.fileId)
                            current = -1
                            setEntry(idx) { it.copy(status = FileStatus.SKIPPED, error = ErrorCode.CONFLICT_SKIPPED) }
                            saveManifestQuiet()
                            io.conn.send(FileResult(e.fileId, FileStatus.SKIPPED, ErrorCode.CONFLICT_SKIPPED))
                            publish(persist = true)
                            return
                        }
                        ConflictPolicy.REPLACE -> if (fs.isDirectory(wanted) || fs.isSymbolicLink(wanted)) {
                            t = freeVariant(wanted, e.name)
                        } else {
                            replace = true
                        }
                        ConflictPolicy.RENAME -> t = freeVariant(wanted, e.name)
                    }
                }
                // Crash marker: if we die after the move, recovery finds the final file by this path.
                setEntry(idx) { it.copy(finalPath = t.toString()) }
                saveManifest()
                pf.close()
                fs.move(pf.partPath, t, replace)
                target = t
            }
        } catch (ex: IllegalArgumentException) {
            failUnsafe(idx, pf); return
        } catch (ex: TransferException) {
            failUnsafe(idx, pf); return
        } catch (ex: IOException) {
            setEntry(idx) { it.copy(finalPath = null) }
            onWriteFailure(ex); return
        } catch (ex: SecurityException) {
            setEntry(idx) { it.copy(finalPath = null) }
            onWriteFailure(ex); return
        }
        e.modifiedAt?.let { t -> runCatching { fs.setLastModified(target, t) } }
        pf.deleteMetadata()
        partials.remove(e.fileId)
        current = -1
        bytesDone[idx] = e.size
        setEntry(idx) { it.copy(status = FileStatus.COMPLETED, error = null) }
        saveManifestQuiet()
        runCatching { engine.onFileReceived(target, e.mimeType) }
        io.conn.send(FileResult(e.fileId, FileStatus.COMPLETED, savedAs = relativeToDest(target)))
        publish(persist = true)
    }

    private fun failUnsafe(idx: Int, pf: PartialFile) {
        val e = manifest.files[idx]
        pf.deleteAll()
        partials.remove(e.fileId)
        current = -1
        setEntry(idx) { it.copy(status = FileStatus.FAILED, error = ErrorCode.UNSAFE_NAME, finalPath = null) }
        saveManifestQuiet()
        io.conn.send(FileResult(e.fileId, FileStatus.FAILED, ErrorCode.UNSAFE_NAME))
        publish(persist = true)
    }

    private fun freeVariant(wanted: Path, name: String): Path {
        for (n in 1..9999) {
            val candidate = wanted.resolveSibling(FileNameSanitizer.numberedVariant(name, n))
            if (!fs.exists(candidate)) return candidate
        }
        throw IOException("no free file name")
    }

    /** Refuses to write through a symbolic link inside the destination tree. */
    private fun ensureNoSymlinks(base: Path, dir: Path) {
        val root = base.toAbsolutePath().normalize()
        val rel = root.relativize(dir.toAbsolutePath().normalize())
        var p = root
        for (part in rel) {
            if (part.toString().isEmpty()) continue
            p = p.resolve(part)
            if (fs.isSymbolicLink(p)) throw TransferException(ErrorCode.UNSAFE_NAME, "symbolic link in destination path")
        }
    }

    private fun onWriteFailure(ex: Throwable) {
        val idx = current
        if (idx < 0) throw TransferException(ErrorCode.WRITE_FAILED, cause = ex)
        val e = manifest.files[idx]
        val available = fs.usableSpace(destination)
        val code = IoErrorClassifier.classifyWrite(ex, available, manifest.chunkSize)
        val pf = partial(e)
        pf.rollbackToCheckpoint()
        bytesDone[idx] = pf.verifiedOffset
        Log.w(TAG, "Write failure on file ${idx + 1} classified as $code; kept ${pf.verifiedOffset} verified bytes")
        block(idx, code, e.size - pf.verifiedOffset + preflight.overheadFor(e.size), available)
    }

    private fun block(idx: Int, code: ErrorCode, required: Long?, available: Long?) {
        val e = manifest.files[idx]
        blockedFile = idx
        discard = idx
        current = -1
        val verified = partials[e.fileId]?.verifiedOffset ?: 0
        setEntry(idx) { it.copy(status = FileStatus.BLOCKED, error = code) }
        state = SessionState.BLOCKED
        error = code
        storageIssue = StorageIssue(code, e.fileId, required, available, destination.toString())
        io.conn.send(Blocked(e.fileId, verified, code, required, available))
        saveManifestQuiet()
        publish(persist = true)
    }

    private fun retryStorage(newDestination: Path?) {
        if (blockedFile < 0) return
        val idx = blockedFile
        if (newDestination != null && newDestination.toAbsolutePath() != destination.toAbsolutePath()) {
            if (!relocate(newDestination)) {
                storageIssue = storageIssue?.copy(reason = ErrorCode.DESTINATION_UNAVAILABLE, destination = newDestination.toString())
                publish(persist = true)
                return
            }
        }
        val e = manifest.files[idx]
        val pf = partial(e)
        val v = try {
            pf.loadAndValidate()
        } catch (ex: IOException) {
            pf.resetFiles(); PartialFile.Validation(0, PartialFile.emptyDigest(), true, "unreadable")
        }
        validated += e.fileId
        bytesDone[idx] = v.verifiedOffset
        val need = e.size - v.verifiedOffset + preflight.overheadFor(e.size)
        val writable = fs.canWrite(partialDir)
        val (room, available) = preflight.hasRoomFor(destination, need)
        if (!writable || !room) {
            val code = if (!writable) ErrorCode.PERMISSION_DENIED else ErrorCode.INSUFFICIENT_STORAGE
            storageIssue = StorageIssue(code, e.fileId, need, available, destination.toString())
            error = code
            io.conn.send(Blocked(e.fileId, v.verifiedOffset, code, need, available))
            publish(persist = true)
            Log.i(TAG, "Storage re-check: still blocked ($code)")
            return
        }
        blockedFile = -1
        state = if (pausedByUser || pausedByPeer) SessionState.PAUSED else SessionState.TRANSFERRING
        error = null
        storageIssue = null
        setEntry(idx) { it.copy(status = FileStatus.PENDING, error = null) }
        saveManifestQuiet()
        io.conn.send(Unblocked(e.fileId, v.verifiedOffset))
        publish(persist = true)
        Log.i(TAG, "Storage re-check passed; resuming file ${idx + 1} at ${v.verifiedOffset}")
    }

    /** Moves this session's partial data to a new destination. */
    private fun relocate(newDest: Path): Boolean {
        val newDir = engine.manifests.partialDir(newDest, id)
        return try {
            fs.createDirectories(newDir)
            if (!fs.canWrite(newDir)) return false
            closePartials()
            for (child in fs.list(partialDir)) fs.move(child, newDir.resolve(child.fileName.toString()), replace = true)
            fs.deleteIfExists(partialDir)
            partialDir.parent?.let { parent -> if (fs.list(parent).isEmpty()) fs.deleteIfExists(parent) }
            partialDir = newDir
            destination = newDest
            partials.clear()
            validated.clear()
            manifest = manifest.copy(destination = newDest.toString())
            saveManifest()
            engine.receiveIndex.put(id, newDir)
            Log.i(TAG, "Moved partial data to new destination")
            true
        } catch (ex: Exception) {
            Log.w(TAG, "Could not move partial data to the new destination", ex)
            false
        }
    }

    private fun onCancelFile(fileId: String, code: ErrorCode) {
        val idx = indexOf(fileId)
        val e = manifest.files[idx]
        if (e.status.terminal) return
        partials.remove(fileId)?.deleteAll()
        validated -= fileId
        bytesDone[idx] = 0
        setEntry(idx) { it.copy(status = FileStatus.CANCELLED, error = code) }
        if (idx == current) {
            current = -1; discard = idx
        }
        if (idx == blockedFile) {
            blockedFile = -1
            state = SessionState.TRANSFERRING
            storageIssue = null
            error = null
        }
        saveManifestQuiet()
        publish(persist = true)
    }

    private fun cancelAll(code: ErrorCode) {
        for (i in manifest.files.indices) {
            val e = manifest.files[i]
            if (e.accepted && !e.status.terminal) {
                partials.remove(e.fileId)?.deleteAll()
                setEntry(i) { it.copy(status = FileStatus.CANCELLED, error = code) }
            }
        }
        state = SessionState.CANCELLED
        error = code
        cleanupSessionDir()
        Log.i(TAG, "Session ${id.take(6)} cancelled ($code); completed files kept")
    }

    private fun finish() {
        for (i in manifest.files.indices) {
            val e = manifest.files[i]
            if (e.accepted && !e.status.terminal) setEntry(i) { it.copy(status = FileStatus.INTERRUPTED) }
        }
        state = SenderSession.finalState(manifest.files.map { FileProgress(it.fileId, it.name, size = it.size, status = it.status) })
        manifest = manifest.copy(state = state)
        if (manifest.files.all { it.status.terminal }) cleanupSessionDir() else saveManifestQuiet()
        Log.i(TAG, "Session ${id.take(6)} finished: $state")
    }

    private fun cleanupSessionDir() {
        closePartials()
        if (!::partialDir.isInitialized) return
        try {
            if (fs.exists(partialDir)) {
                for (child in fs.list(partialDir)) fs.deleteIfExists(child)
                fs.deleteIfExists(partialDir)
            }
            partialDir.parent?.let { parent -> if (fs.exists(parent) && fs.list(parent).isEmpty()) fs.deleteIfExists(parent) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove temporary data", e)
        }
        engine.receiveIndex.remove(id)
    }

    private fun closePartials() = partials.values.forEach { it.close() }

    private fun partial(e: ReceiveFileEntry): PartialFile =
        partials.getOrPut(e.fileId) { PartialFile(fs, partialDir, e.fileId, e.size, manifest.chunkSize) }

    private fun indexOf(fileId: String): Int {
        val idx = manifest.files.indexOfFirst { it.fileId == fileId }
        if (idx < 0) throw TransferException(ErrorCode.PROTOCOL_ERROR, "unknown file id")
        return idx
    }

    private fun setEntry(i: Int, transform: (ReceiveFileEntry) -> ReceiveFileEntry) {
        manifest = manifest.copy(files = manifest.files.toMutableList().also { it[i] = transform(it[i]) })
    }

    private fun saveManifest() {
        manifest = manifest.copy(updatedAt = System.currentTimeMillis())
        engine.manifests.save(partialDir, manifest)
    }

    /** Manifest writes can fail when the disk is full. Checkpoints, not the manifest, guard data. */
    private fun saveManifestQuiet() {
        if (!::partialDir.isInitialized) return
        try {
            saveManifest()
        } catch (e: Exception) {
            Log.w(TAG, "Manifest save failed (${e.javaClass.simpleName}); will retry")
        }
    }

    fun committedRemainingBytes(): Long {
        if (!::manifest.isInitialized || state.terminal) return 0
        return manifest.files.filter { it.accepted && !it.status.terminal }.sumOf { (it.size - bytesDone[it.index]).coerceAtLeast(0) }
    }

    fun destinationOrNull(): Path? = if (::destination.isInitialized) destination else null

    val peerFingerprint: String get() = peer.fingerprint

    /** Drops this (stale) connection so a reconnecting sender can take the session over. */
    fun abortStale() = io.close()

    companion object {
        private const val TAG = "Receiver"
    }
}
