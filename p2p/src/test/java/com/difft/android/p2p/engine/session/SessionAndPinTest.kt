package com.difft.android.p2p.engine.session

import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.ProtocolError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-S4, T-S6, T-S8, T-S9 — session manager + PIN policy (design §13). */
class SessionAndPinTest {

    private fun meta(id: String) = FileMetadata(id = id, fileName = "$id.bin", size = 4, fileType = "application/octet-stream")

    @Test
    fun `T-S4 second prepare-upload while one active is Conflict`() {
        val sm = SessionManager()
        sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        assertThrows(ProtocolError.Conflict::class.java) {
            sm.createSession("2.2.2.2", mapOf("f2" to meta("f2")))
        }
    }

    @Test
    fun `empty files map is NoContent`() {
        val sm = SessionManager()
        assertThrows(ProtocolError.NoContent::class.java) {
            sm.createSession("1.1.1.1", emptyMap())
        }
    }

    @Test
    fun `authorizeUpload succeeds for matching token and ip`() {
        val sm = SessionManager()
        val (sid, slots) = sm.createSession("10.0.0.5", mapOf("f1" to meta("f1")))
        val token = slots.getValue("f1").token
        val slot = sm.authorizeUpload(sid, "f1", token, "10.0.0.5")
        assertEquals("f1", slot.meta.id)
    }

    @Test
    fun `T-S6 authorizeUpload from a different ip is Forbidden`() {
        val sm = SessionManager()
        val (sid, slots) = sm.createSession("10.0.0.5", mapOf("f1" to meta("f1")))
        val token = slots.getValue("f1").token
        assertThrows(ProtocolError.Forbidden::class.java) {
            sm.authorizeUpload(sid, "f1", token, "10.0.0.9") // wrong source IP
        }
    }

    @Test
    fun `authorizeUpload with wrong token is Forbidden`() {
        val sm = SessionManager()
        val (sid, _) = sm.createSession("10.0.0.5", mapOf("f1" to meta("f1")))
        assertThrows(ProtocolError.Forbidden::class.java) {
            sm.authorizeUpload(sid, "f1", "wrong-token", "10.0.0.5")
        }
    }

    @Test
    fun `cancel frees the session so a new one can start`() {
        val sm = SessionManager()
        val prepared = sm.createSession("1.1.1.1", mapOf("f1" to meta("f1")))
        sm.cancel(prepared.sessionId, prepared.sessionToken)
        // No Conflict now:
        sm.createSession("2.2.2.2", mapOf("f2" to meta("f2")))
    }

    @Test
    fun `T-S8 PIN is one-time and expires`() {
        var now = 0L
        val policy = PinPolicy(ttlMillis = 1000, clock = { now })
        val pin = policy.issue()
        assertTrue(policy.validateAndConsume(pin))   // first use OK
        assertFalse(policy.validateAndConsume(pin))   // reused → rejected

        val policy2 = PinPolicy(ttlMillis = 1000, clock = { now })
        val pin2 = policy2.issue()
        now = 2000 // past TTL
        assertFalse("expired PIN rejected", policy2.validateAndConsume(pin2))
    }

    @Test
    fun `PIN wrong value rejected`() {
        val policy = PinPolicy()
        policy.issue()
        assertFalse(policy.validateAndConsume("000000-wrong"))
        assertFalse(policy.validateAndConsume(null))
    }

    @Test
    fun `PIN is invalidated after MAX_PIN_ATTEMPTS wrong guesses`() {
        val policy = PinPolicy()
        val pin = policy.issue()
        repeat(PinPolicy.MAX_PIN_ATTEMPTS) { assertFalse(policy.validateAndConsume("000000")) }
        assertFalse("correct PIN must be rejected once the attempt cap is hit", policy.validateAndConsume(pin))
    }

    @Test
    fun `PIN still accepted on the last attempt before the cap`() {
        val policy = PinPolicy()
        val pin = policy.issue()
        repeat(PinPolicy.MAX_PIN_ATTEMPTS - 1) { assertFalse(policy.validateAndConsume("000000")) }
        assertTrue("a correct PIN below the cap must still be accepted", policy.validateAndConsume(pin))
    }

    @Test
    fun `expired PIN does not consume attempts and clears state`() {
        var now = 0L
        val policy = PinPolicy(ttlMillis = 1000, clock = { now })
        val pin = policy.issue()
        now = 2000 // past TTL
        // Far more probes than the cap — an expired PIN must not act as a free attempt counter.
        repeat(PinPolicy.MAX_PIN_ATTEMPTS * 3) { assertFalse(policy.validateAndConsume("000000")) }
        // Expiry cleared the state, so the old PIN is gone regardless of the clock.
        assertNull("expiry must clear the active PIN", policy.current())
        now = 0L
        assertFalse("expired-then-cleared PIN must not come back", policy.validateAndConsume(pin))

        // A fresh issue works normally with a full attempt budget.
        val fresh = policy.issue()
        repeat(PinPolicy.MAX_PIN_ATTEMPTS - 1) { assertFalse(policy.validateAndConsume("000000")) }
        assertTrue(policy.validateAndConsume(fresh))
    }

    @Test
    fun `issue resets the failed-attempt counter`() {
        val policy = PinPolicy()
        policy.issue()
        repeat(PinPolicy.MAX_PIN_ATTEMPTS - 1) { assertFalse(policy.validateAndConsume("000000")) }
        val pin = policy.issue() // fresh PIN → budget back to full
        repeat(PinPolicy.MAX_PIN_ATTEMPTS - 1) { assertFalse(policy.validateAndConsume("000000")) }
        assertTrue("issue() must reset the attempt counter", policy.validateAndConsume(pin))
    }

    @Test
    fun `lockout response is uniform for correct wrong and null candidates`() {
        val policy = PinPolicy()
        val pin = policy.issue()
        repeat(PinPolicy.MAX_PIN_ATTEMPTS) { assertFalse(policy.validateAndConsume("000000")) }
        // No distinguishable behavior post-lockout: all inputs return the same false.
        assertFalse(policy.validateAndConsume(pin))
        assertFalse(policy.validateAndConsume("000000"))
        assertFalse(policy.validateAndConsume(null))
        assertNull("lockout must clear the active PIN", policy.current())
    }

    @Test
    fun `T-S9 concurrent createSession yields exactly one winner`() = runTest {
        val sm = SessionManager()
        val results = (1..16).map { i ->
            async {
                runCatching { sm.createSession("1.1.1.$i", mapOf("f$i" to meta("f$i"))) }.isSuccess
            }
        }.awaitAll()
        assertEquals("exactly one createSession must win the single-active-session race", 1, results.count { it })
    }
}
