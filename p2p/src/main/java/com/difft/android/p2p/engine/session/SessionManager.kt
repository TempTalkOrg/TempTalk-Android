package com.difft.android.p2p.engine.session

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.model.CancelState
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.model.StatusResponse
import com.difft.android.p2p.engine.model.TransferStages
import com.difft.android.p2p.engine.model.TransferTerminal
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID







class FileSlot internal constructor(
    val meta: FileMetadata,
    val token: String,
) {
    @Volatile var done: Boolean = false
        internal set
}





data class PreparedSession(
    val sessionId: String,
    val slots: Map<String, FileSlot>,
    val sessionToken: String,
)







data class CancelOutcome(val state: String, val tornDown: Boolean)

















class SessionManager(
    private val random: SecureRandom = SecureRandom(),
    private val terminalStore: TerminalRecordStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : TransferProgressReporter {
    private val lock = Any()

    private class Session(
        val sessionId: String,
        val sessionToken: String,
        val sourceIp: String,
        val slots: Map<String, FileSlot>, 
        createdAt: Long,
    ) {
        var stage: String = TransferStages.PREPARED
        var records: Long = 0
        var attachmentBytes: Long = 0
        var progressRevision: Long = 0
        var updatedAt: Long = createdAt
        
        var committing: Boolean = false
        
        var bodyStarted: Boolean = false
        
        var unconfirmed: Boolean = false
    }

    private var active: Session? = null

    
    private var sessionOpened = false

    
    private val retained = ArrayDeque<TerminalRecord>()

    init {
        terminalStore?.let { store ->
            val now = clock()
            val kept = runCatching { store.load() }.getOrDefault(emptyList())
                .filter { it.at <= now && now - it.at <= RETENTION_MS }
                .sortedBy { it.at }
                .takeLast(MAX_RETAINED)
            retained.addAll(kept)
        }
    }

    




    fun createSession(sourceIp: String, files: Map<String, FileMetadata>): PreparedSession =
        synchronized(lock) {
            if (files.isEmpty()) throw ProtocolError.NoContent
            if (active != null) throw ProtocolError.Conflict

            val sessionId = UUID.randomUUID().toString()
            val slots = files.mapValues { (_, meta) -> FileSlot(meta, newFileToken()) }
            val session = Session(sessionId, newSessionToken(), sourceIp, slots, clock())
            active = session
            sessionOpened = true
            PreparedSession(sessionId, slots, session.sessionToken)
        }

    




    fun authorizeUpload(sessionId: String, fileId: String, token: String, sourceIp: String): FileSlot =
        synchronized(lock) {
            val session = active ?: throw ProtocolError.Forbidden("no active session")
            if (session.sessionId != sessionId) throw ProtocolError.Forbidden("session mismatch")
            if (session.sourceIp != sourceIp) throw ProtocolError.Forbidden("ip mismatch")
            val slot = session.slots[fileId] ?: throw ProtocolError.BadRequest("unknown fileId")
            if (slot.token != token) throw ProtocolError.Forbidden("token mismatch")
            session.bodyStarted = true
            slot
        }

    
    fun markDone(sessionId: String, fileId: String) {
        synchronized(lock) {
            val session = active ?: return
            if (session.sessionId != sessionId) return
            session.slots[fileId]?.done = true
        }
    }

    
    fun isComplete(sessionId: String): Boolean {
        synchronized(lock) {
            val session = active ?: return false
            if (session.sessionId != sessionId) return false
            return session.slots.values.all { it.done }
        }
    }

    






    fun status(sessionId: String, token: String): StatusResponse = synchronized(lock) {
        active?.takeIf { it.sessionId == sessionId }?.let { s ->
            requireToken(s.sessionToken, token, "status")
            return StatusResponse(
                stage = s.stage,
                records = s.records,
                attachmentBytes = s.attachmentBytes,
                progressRevision = s.progressRevision,
                terminal = null,
                updatedAt = s.updatedAt,
            )
        }
        val record = retained.lastOrNull { it.sessionId == sessionId } ?: throw ProtocolError.NotFound
        requireToken(record.tokenSha256, sha256Hex(token), "status")
        StatusResponse(
            stage = record.stage,
            records = record.records,
            attachmentBytes = record.attachmentBytes,
            progressRevision = record.progressRevision,
            terminal = record.terminal,
            updatedAt = record.at,
        )
    }

    









    fun cancel(sessionId: String, token: String): CancelOutcome = synchronized(lock) {
        active?.takeIf { it.sessionId == sessionId }?.let { s ->
            requireToken(s.sessionToken, token, "cancel")
            if (s.committing) return CancelOutcome(CancelState.TOO_LATE, tornDown = false)
            val state = if (s.bodyStarted) CancelState.ROLLED_BACK else CancelState.STOPPED
            retire(s, TransferTerminal.CANCELLED)
            return CancelOutcome(state, tornDown = true)
        }
        val record = retained.lastOrNull { it.sessionId == sessionId } ?: throw ProtocolError.NotFound
        requireToken(record.tokenSha256, sha256Hex(token), "cancel")
        
        
        
        if (record.terminal == TransferTerminal.FAILED && clock() - record.at <= CANCEL_FOLLOW_UP_MS) {
            val index = retained.lastIndexOf(record)
            retained[index] = record.copy(terminal = TransferTerminal.CANCELLED, stage = TransferStages.CANCELLED)
            terminalStore?.let { store -> runCatching { store.save(retained.toList()) } }
            return CancelOutcome(CancelState.ROLLED_BACK, tornDown = true)
        }
        
        
        
        val state = when (record.terminal) {
            TransferTerminal.SUCCEEDED -> CancelState.TOO_LATE
            TransferTerminal.FAILED -> CancelState.ROLLED_BACK
            
            
            null -> CancelState.TOO_LATE
            else -> CancelState.STOPPED
        }
        CancelOutcome(state, tornDown = false)
    }

    








    fun markTerminal(sessionId: String, terminal: String) {
        synchronized(lock) {
            active?.takeIf { it.sessionId == sessionId }?.let { s ->
                retire(s, terminal)
                return
            }
            if (terminal != TransferTerminal.FAILED && terminal != TransferTerminal.SUCCEEDED) return
            val index = retained.indexOfLast { it.sessionId == sessionId }
            val record = retained.getOrNull(index)?.takeIf { it.terminal == null } ?: return
            retained[index] = record.copy(
                terminal = terminal,
                stage = if (terminal == TransferTerminal.SUCCEEDED) TransferStages.DONE else TransferStages.FAILED,
                progressRevision = record.progressRevision + 1,
                at = clock(),
            )
            L.i { "[P2P][Receiver] retained verdict filled terminal=$terminal" }
            terminalStore?.let { store -> runCatching { store.save(retained.toList()) } }
        }
    }

    




    fun markUnconfirmed(sessionId: String) {
        synchronized(lock) {
            active?.takeIf { it.sessionId == sessionId }?.unconfirmed = true
        }
    }

    




    fun clear() {
        synchronized(lock) {
            active?.let(::retireUnfinished)
        }
    }

    





    private fun retireUnfinished(s: Session) {
        if (s.unconfirmed || s.committing) retire(s, terminal = null) else retire(s, TransferTerminal.CANCELLED)
    }

    




    fun retireLiveForLinger(): Boolean = synchronized(lock) {
        active?.let(::retireUnfinished)
        sessionOpened
    }

    
    fun activeSessionId(): String? = synchronized(lock) { active?.sessionId }

    

    override fun stage(stage: String) = bump { s ->
        if (s.stage == stage) return@bump false
        s.stage = stage
        true
    }

    override fun recordsImported(total: Long) = bump { s ->
        if (total <= s.records) return@bump false
        s.records = total
        true
    }

    override fun attachmentBytesLanded(delta: Long) = bump { s ->
        if (delta <= 0) return@bump false
        s.attachmentBytes += delta
        true
    }

    override fun bodySessionId(): String? = synchronized(lock) { active?.takeIf { it.bodyStarted }?.sessionId }

    





    override fun committing(sessionId: String?): Boolean = synchronized(lock) {
        val s = active
        if (sessionId == null || s == null || s.sessionId != sessionId || !s.bodyStarted) {
            L.w { "[P2P][Receiver] event=commit_refused active=${s != null} sameSession=${s != null && s.sessionId == sessionId}" }
            return false
        }
        if (!s.committing || s.stage != TransferStages.PROMOTING) {
            s.committing = true
            s.stage = TransferStages.PROMOTING
            s.progressRevision++
            s.updatedAt = clock()
        }
        true
    }

    
    private fun bump(change: (Session) -> Boolean) {
        synchronized(lock) {
            val s = active ?: return
            if (change(s)) {
                s.progressRevision++
                s.updatedAt = clock()
            }
        }
    }

    




    private fun retire(s: Session, terminal: String?) {
        s.stage = when (terminal) {
            TransferTerminal.SUCCEEDED -> TransferStages.DONE
            TransferTerminal.FAILED -> TransferStages.FAILED
            null -> TransferStages.PROMOTING
            else -> TransferStages.CANCELLED
        }
        s.progressRevision++
        retained.addLast(
            TerminalRecord(
                sessionId = s.sessionId,
                tokenSha256 = sha256Hex(s.sessionToken),
                terminal = terminal,
                stage = s.stage,
                records = s.records,
                attachmentBytes = s.attachmentBytes,
                progressRevision = s.progressRevision,
                at = clock(),
            ),
        )
        while (retained.size > MAX_RETAINED) retained.removeFirst()
        active = null
        terminalStore?.let { store -> runCatching { store.save(retained.toList()) } }
    }

    
    private fun requireToken(expected: String, presented: String, route: String) {
        if (!constantTimeEquals(expected, presented)) {
            L.w { "[P2P][Receiver] $route refused: session token mismatch" }
            throw ProtocolError.NotFound
        }
    }

    private fun newFileToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    
    private fun newSessionToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        
        const val MAX_RETAINED = 8

        
        const val RETENTION_MS = 24L * 60 * 60 * 1000

        
        const val CANCEL_FOLLOW_UP_MS = 10_000L

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
