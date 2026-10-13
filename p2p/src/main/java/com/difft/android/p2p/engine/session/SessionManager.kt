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

/**
 * Per-file authorization state inside an active session.
 *
 * @property token the per-file authorization token returned in the prepare-upload `files` map.
 * @property done flipped true once the file has been fully uploaded + integrity-verified.
 */
class FileSlot internal constructor(
    val meta: FileMetadata,
    val token: String,
) {
    @Volatile var done: Boolean = false
        internal set
}

/**
 * What `prepare-upload` hands back: the session, its per-file slots, and the session-level token the
 * sender presents on `/status` and `/cancel`.
 */
data class PreparedSession(
    val sessionId: String,
    val slots: Map<String, FileSlot>,
    val sessionToken: String,
)

/**
 * How a `/cancel` was answered. [state] is one of [CancelState] and goes on the wire; [tornDown] is
 * for the owner — true only when this call ended a live session, which is when the peer's leaving
 * is worth reporting as an event. A cancel that arrives too late, or for a session already over,
 * changes nothing and is not an event.
 */
data class CancelOutcome(val state: String, val tornDown: Boolean)

/**
 * Holds the single active upload session: `sessionId → fileId → token`, bound to the sender's source
 * IP. Enforces single-active-session (409) and token+IP authorization (403).
 *
 * It also keeps the last few sessions' terminal records — how each one ended, with the token's hash —
 * so a sender whose final response was lost can still ask `/status` and get a definite answer. The
 * records go to a [TerminalRecordStore] to outlive the process; this class decides what to keep
 * ([MAX_RETAINED] newest, none older than [RETENTION_MS]).
 *
 * It is also the [TransferProgressReporter] the receiving side writes into: every real change bumps
 * the session's `progressRevision`, which is the one figure a sender may read liveness from.
 *
 * Thread-safety: every operation is `synchronized(lock)`, so a second `prepare-upload` racing an
 * in-flight `upload` cannot observe a half-built session and a `/status` read from another
 * connection sees one consistent snapshot.
 */
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
        val slots: Map<String, FileSlot>, // fileId -> slot
        createdAt: Long,
    ) {
        var stage: String = TransferStages.PREPARED
        var records: Long = 0
        var attachmentBytes: Long = 0
        var progressRevision: Long = 0
        var updatedAt: Long = createdAt
        /** Past the point where a cancel can undo the import. */
        var committing: Boolean = false
        /** An `upload` was authorized: bytes may have landed, so a cancel is a roll-back, not a no-op. */
        var bodyStarted: Boolean = false
        /** Committed past the point of no return, with no way to prove the tail finished. */
        var unconfirmed: Boolean = false
    }

    private var active: Session? = null

    /** Newest last. Only records within [RETENTION_MS] of the clock survive a reload. */
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

    /**
     * Creates a new session for [sourceIp] over [files] (fileId -> metadata). Issues one token per
     * file plus one session token. Throws [ProtocolError.Conflict] if a session is already active,
     * [ProtocolError.NoContent] if [files] is empty.
     */
    fun createSession(sourceIp: String, files: Map<String, FileMetadata>): PreparedSession =
        synchronized(lock) {
            if (files.isEmpty()) throw ProtocolError.NoContent
            if (active != null) throw ProtocolError.Conflict

            val sessionId = UUID.randomUUID().toString()
            val slots = files.mapValues { (_, meta) -> FileSlot(meta, newFileToken()) }
            val session = Session(sessionId, newSessionToken(), sourceIp, slots, clock())
            active = session
            PreparedSession(sessionId, slots, session.sessionToken)
        }

    /**
     * Authorizes an `upload` call: the session must be active, [sessionId] must match, the [fileId]
     * must exist with the given [token], and [sourceIp] must match the session's bound IP. Returns the
     * [FileSlot] on success; throws the appropriate [ProtocolError] (400/403) otherwise.
     */
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

    /** Marks [fileId] done in the active [sessionId] (called by the upload handler on success). */
    fun markDone(sessionId: String, fileId: String) {
        synchronized(lock) {
            val session = active ?: return
            if (session.sessionId != sessionId) return
            session.slots[fileId]?.done = true
        }
    }

    /** True iff every file in the active [sessionId] is done. */
    fun isComplete(sessionId: String): Boolean {
        synchronized(lock) {
            val session = active ?: return false
            if (session.sessionId != sessionId) return false
            return session.slots.values.all { it.done }
        }
    }

    /**
     * The sender asking how its session is doing. A live session answers with its progress and no
     * terminal; a retained one with how it ended. The token must be the session token — checked in
     * constant time against the live token, or its hash against the record. Unknown session and
     * wrong token are both 404: the wire must not confirm that an id exists to a caller who cannot
     * prove it owns it. The receiver's own log keeps the two apart for debugging.
     */
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

    /**
     * The sender's `/cancel`. Only a live session that has not passed its commit point is torn down;
     * the answer says what that meant for the data ([CancelState.STOPPED] before any byte landed,
     * [CancelState.ROLLED_BACK] after). A session already promoting is left alone and answered
     * [CancelState.TOO_LATE]; one that already ended is answered by how it ended. An unknown id, or a
     * token that does not match, is 404 exactly as on `/status` — the receiver never answers
     * "unknown"; that word is the sender's own state for a question it could not get answered.
     * [token] is the session token and must match — there are no senders without one, so leniency
     * here would only leave "anyone who knows a sessionId can cancel it".
     */
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
        // A sender's cancel closes its socket first and posts /cancel after, so the body-read failure
        // lands here before this request. A FAILED record that fresh was the peer leaving: re-label it
        // and report a torn-down session so the owner hears PeerCancelled, not a dropped link.
        if (record.terminal == TransferTerminal.FAILED && clock() - record.at <= CANCEL_FOLLOW_UP_MS) {
            val index = retained.lastIndexOf(record)
            retained[index] = record.copy(terminal = TransferTerminal.CANCELLED, stage = TransferStages.CANCELLED)
            terminalStore?.let { store -> runCatching { store.save(retained.toList()) } }
            return CancelOutcome(CancelState.ROLLED_BACK, tornDown = true)
        }
        // The peer asked to stop something that already ended: answer by how it ended. A finished
        // import cannot be undone any more than a running one past its commit point can, so it is
        // `too_late` too — the caller reads the actual terminal from /status, never from this.
        val state = when (record.terminal) {
            TransferTerminal.SUCCEEDED -> CancelState.TOO_LATE
            TransferTerminal.FAILED -> CancelState.ROLLED_BACK
            // Committed without a verdict: the import is past its commit point, so a cancel can undo
            // nothing — the same answer a promoting session gets.
            null -> CancelState.TOO_LATE
            else -> CancelState.STOPPED
        }
        CancelOutcome(state, tornDown = false)
    }

    /**
     * The transfer ended: [terminal] is one of [TransferTerminal]. Moves the live session into the
     * retained records so `/status` keeps answering after the upload connection is gone. A session
     * that already ended (a cancel got there first) is left as it was.
     */
    fun markTerminal(sessionId: String, terminal: String) {
        synchronized(lock) {
            val s = active?.takeIf { it.sessionId == sessionId } ?: return
            retire(s, terminal)
        }
    }

    /**
     * The active session committed its import but cannot prove the tail after it finished. No
     * terminal may ever be published for it, so it is flagged here and [clear] retires it without
     * one instead of calling the teardown a cancellation.
     */
    fun markUnconfirmed(sessionId: String) {
        synchronized(lock) {
            active?.takeIf { it.sessionId == sessionId }?.unconfirmed = true
        }
    }

    /**
     * Unconditional teardown (receiver stop). A session still live at this point was ended by this
     * side — the receiver going away — which for the sender is a cancel, so it is retained as one; a
     * session that already reached its terminal is untouched.
     */
    fun clear() {
        synchronized(lock) {
            active?.let { s ->
                // A committed-but-unconfirmed session must not be retired as CANCELLED: that publishes
                // a definite, false terminal — and persists it for the whole retention window —
                // minutes after the data was already promoted.
                if (s.unconfirmed) retire(s, terminal = null) else retire(s, TransferTerminal.CANCELLED)
            }
        }
    }

    /** The active sessionId, or null. (Diagnostics / demo display.) */
    fun activeSessionId(): String? = synchronized(lock) { active?.sessionId }

    // ── TransferProgressReporter ─────────────────────────────────────────────────────────────

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

    override fun committing() = bump { s ->
        val changed = !s.committing || s.stage != TransferStages.PROMOTING
        s.committing = true
        s.stage = TransferStages.PROMOTING
        changed
    }

    /** Applies [change] to the live session; a `true` return is real work and bumps the revision. */
    private fun bump(change: (Session) -> Boolean) {
        synchronized(lock) {
            val s = active ?: return
            if (change(s)) {
                s.progressRevision++
                s.updatedAt = clock()
            }
        }
    }

    /**
     * Under [lock]: end [s] with [terminal], retain its record, persist, and drop it as active. A null
     * [terminal] retires the session with no verdict at all, leaving `/status` answering
     * "still promoting, no result" for the retention window.
     */
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

    /** Wire: 404, indistinguishable from an unknown session. Log: the real reason, for this device only. */
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

    /** 128 bits, base64url without padding — the shape the cross-platform contract fixes. */
    private fun newSessionToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        /** How many ended sessions are remembered. */
        const val MAX_RETAINED = 8

        /** How long a terminal record is remembered. */
        const val RETENTION_MS = 24L * 60 * 60 * 1000

        /** A /cancel this soon after a body-read failure is the sender that hung up. */
        const val CANCEL_FOLLOW_UP_MS = 10_000L

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
