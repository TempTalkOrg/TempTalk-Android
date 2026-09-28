package com.difft.android.p2p.engine.session

import com.difft.android.p2p.engine.model.CancelState
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.model.TransferStages
import com.difft.android.p2p.engine.model.TransferTerminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session token, `/status`, the four-state `/cancel` and terminal retention — the receiver half
 * of the verdict-wait contract. Each rule here is one a sender is allowed to rely on when it decides
 * whether the old device may start over, or has been superseded.
 */
class SessionStatusAndCancelTest {

    private class MemoryStore : TerminalRecordStore {
        var saved: List<TerminalRecord> = emptyList()
        var toLoad: List<TerminalRecord> = emptyList()
        override fun load() = toLoad
        override fun save(records: List<TerminalRecord>) { saved = records }
    }

    private fun meta(id: String) = FileMetadata(id = id, fileName = "$id.bin", size = 4, fileType = "application/octet-stream")

    private fun manager(store: TerminalRecordStore? = null, clock: () -> Long = { 1_000L }) =
        SessionManager(terminalStore = store, clock = clock)

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `prepare issues a 128-bit base64url session token distinct from the file tokens`() {
        val prepared = manager().createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        assertEquals(22, prepared.sessionToken.length)
        assertTrue(prepared.sessionToken.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(prepared.slots.getValue("f1").token, prepared.sessionToken)
    }

    // region status

    @Test
    fun `status answers a live session with its progress and no terminal, and only for the right token`() {
        val sm = manager()
        val p = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))

        val fresh = sm.status(p.sessionId, p.sessionToken)
        assertEquals(TransferStages.PREPARED, fresh.stage)
        assertNull(fresh.terminal)
        assertEquals(0L, fresh.progressRevision)

        // Wrong token and unknown id are the same answer on the wire: neither confirms the id exists.
        assertThrows(ProtocolError.NotFound::class.java) { sm.status(p.sessionId, "wrong") }
        assertThrows(ProtocolError.NotFound::class.java) { sm.status("never-existed", p.sessionToken) }
    }

    @Test
    fun `a cancel right after a body-read failure is the peer leaving, an old failure stays failed`() {
        var now = 1_000L
        val sm = manager(MemoryStore()) { now }
        val p = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        sm.authorizeUpload(p.sessionId, "f1", p.slots.getValue("f1").token, "1.1.1.1")
        sm.markTerminal(p.sessionId, TransferTerminal.FAILED)

        now += 500
        val fresh = sm.cancel(p.sessionId, p.sessionToken)
        assertTrue(fresh.tornDown)
        assertEquals(CancelState.ROLLED_BACK, fresh.state)
        assertEquals(TransferTerminal.CANCELLED, sm.status(p.sessionId, p.sessionToken).terminal)

        val q = sm.createSession("1.1.1.1", mapOf("f2" to meta("f2")))
        sm.authorizeUpload(q.sessionId, "f2", q.slots.getValue("f2").token, "1.1.1.1")
        sm.markTerminal(q.sessionId, TransferTerminal.FAILED)
        now += SessionManager.CANCEL_FOLLOW_UP_MS + 1
        val stale = sm.cancel(q.sessionId, q.sessionToken)
        assertFalse(stale.tornDown)
        assertEquals(TransferTerminal.FAILED, sm.status(q.sessionId, q.sessionToken).terminal)
    }

    @Test
    fun `progressRevision moves with every real change and with nothing else`() {
        val sm = manager()
        val p = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        fun rev() = sm.status(p.sessionId, p.sessionToken).progressRevision

        sm.stage(TransferStages.RECEIVING); assertEquals(1L, rev())
        sm.stage(TransferStages.RECEIVING); assertEquals(1L, rev())          // same stage: not work
        sm.recordsImported(10); assertEquals(2L, rev())
        sm.recordsImported(10); assertEquals(2L, rev())                      // no new records: not work
        sm.recordsImported(5); assertEquals(2L, rev())                       // a lower total is ignored
        // The rule the stall detector depends on: bytes landing is work, every time, by itself.
        sm.attachmentBytesLanded(8_192); assertEquals(3L, rev())
        sm.attachmentBytesLanded(8_192); assertEquals(4L, rev())
        sm.attachmentBytesLanded(0); assertEquals(4L, rev())
        assertEquals(16_384L, sm.status(p.sessionId, p.sessionToken).attachmentBytes)
        assertEquals(10L, sm.status(p.sessionId, p.sessionToken).records)
    }

    @Test
    fun `reporting with no live session is a no-op`() {
        val sm = manager()
        sm.stage(TransferStages.RECEIVING)
        sm.recordsImported(3)
        sm.attachmentBytesLanded(1)
        sm.committing()
        assertNull(sm.activeSessionId())
    }

    // endregion

    // region cancel

    @Test
    fun `cancel before any byte is stopped, after the upload was authorized it is rolled_back, both tear down`() {
        val a = manager()
        val pa = a.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        assertEquals(CancelOutcome(CancelState.STOPPED, tornDown = true), a.cancel(pa.sessionId, pa.sessionToken))
        assertNull(a.activeSessionId())

        val b = manager()
        val pb = b.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        b.authorizeUpload(pb.sessionId, "f1", pb.slots.getValue("f1").token, "1.1.1.1")
        assertEquals(CancelOutcome(CancelState.ROLLED_BACK, tornDown = true), b.cancel(pb.sessionId, pb.sessionToken))
        assertNull(b.activeSessionId())
    }

    @Test
    fun `cancel past the commit point is too_late and the session stays live`() {
        val sm = manager()
        val p = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        sm.committing()
        assertEquals(CancelOutcome(CancelState.TOO_LATE, tornDown = false), sm.cancel(p.sessionId, p.sessionToken))
        assertEquals(p.sessionId, sm.activeSessionId())
        assertEquals(TransferStages.PROMOTING, sm.status(p.sessionId, p.sessionToken).stage)
    }

    @Test
    fun `cancel of a finished session is too_late too, and of a failed or cancelled one says how it ended`() {
        var now = 1_000L
        val sm = manager(clock = { now })
        val p1 = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        sm.markTerminal(p1.sessionId, TransferTerminal.SUCCEEDED)
        // Done is past the commit point by definition: the caller reads the real outcome from /status.
        assertEquals(CancelOutcome(CancelState.TOO_LATE, tornDown = false), sm.cancel(p1.sessionId, p1.sessionToken))

        val p2 = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        sm.markTerminal(p2.sessionId, TransferTerminal.FAILED)
        // Long after the failure: this cancel is not the hang-up's follow-up.
        now += SessionManager.CANCEL_FOLLOW_UP_MS + 1
        assertEquals(CancelOutcome(CancelState.ROLLED_BACK, tornDown = false), sm.cancel(p2.sessionId, p2.sessionToken))

        val p3 = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        sm.cancel(p3.sessionId, p3.sessionToken)
        assertEquals(CancelOutcome(CancelState.STOPPED, tornDown = false), sm.cancel(p3.sessionId, p3.sessionToken))
    }

    @Test
    fun `cancel with an unknown id or a wrong token is 404, and nothing is decided`() {
        val sm = manager()
        val p = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        assertThrows(ProtocolError.NotFound::class.java) { sm.cancel("nope", p.sessionToken) }
        assertThrows(ProtocolError.NotFound::class.java) { sm.cancel(p.sessionId, "wrong") }
        assertEquals(p.sessionId, sm.activeSessionId())
    }

    // endregion

    // region terminal retention

    @Test
    fun `a finished session keeps answering status with its terminal, from the record not the token`() {
        val store = MemoryStore()
        val sm = manager(store)
        val p = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        sm.recordsImported(42)
        sm.markTerminal(p.sessionId, TransferTerminal.SUCCEEDED)

        val ended = sm.status(p.sessionId, p.sessionToken)
        assertEquals(TransferTerminal.SUCCEEDED, ended.terminal)
        assertEquals(TransferStages.DONE, ended.stage)
        assertEquals(42L, ended.records)
        assertThrows(ProtocolError.NotFound::class.java) { sm.status(p.sessionId, "wrong") }

        // The persisted record carries the token's hash, never the token.
        val record = store.saved.single()
        assertEquals(p.sessionId, record.sessionId)
        assertNotEquals(p.sessionToken, record.tokenSha256)
        assertEquals(64, record.tokenSha256.length)
        assertFalse(store.saved.toString().contains(p.sessionToken))
    }

    @Test
    fun `a session still live at clear is retained as cancelled, a finished one is untouched`() {
        val store = MemoryStore()
        val sm = manager(store)
        val p = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        sm.clear()
        assertEquals(TransferTerminal.CANCELLED, sm.status(p.sessionId, p.sessionToken).terminal)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun `retention keeps the newest eight and drops records older than a day on reload`() {
        var now = 1_000L
        val store = MemoryStore()
        val sm = manager(store) { now }
        val ids = (1..10).map { i ->
            now = 1_000L + i
            val p = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
            sm.markTerminal(p.sessionId, TransferTerminal.SUCCEEDED)
            p
        }
        assertEquals(SessionManager.MAX_RETAINED, store.saved.size)
        assertThrows(ProtocolError.NotFound::class.java) { sm.status(ids[0].sessionId, ids[0].sessionToken) }
        assertEquals(TransferTerminal.SUCCEEDED, sm.status(ids[9].sessionId, ids[9].sessionToken).terminal)

        // Reload a day and a bit later: the whole batch has aged out; a fresh one has not.
        val fresh = TerminalRecord("s-fresh", "h", TransferTerminal.FAILED, TransferStages.FAILED, 0, 0, 1, at = 2_000L)
        store.toLoad = store.saved + fresh
        val later = SessionManager(terminalStore = store, clock = { 2_000L + SessionManager.RETENTION_MS })
        assertThrows(ProtocolError.NotFound::class.java) { later.status(ids[9].sessionId, ids[9].sessionToken) }
        // The fresh record is present: a matching hash answers, from the reloaded record alone.
        val freshToken = "the-token"
        store.toLoad = store.saved + fresh.copy(tokenSha256 = sha256Hex(freshToken))
        val reloaded = SessionManager(terminalStore = store, clock = { 2_000L + SessionManager.RETENTION_MS })
        assertEquals(TransferTerminal.FAILED, reloaded.status("s-fresh", freshToken).terminal)
    }

    // endregion
}
