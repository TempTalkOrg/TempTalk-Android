package com.difft.android.p2p.engine.upload

import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.p2p.engine.model.PrepareUploadRequest
import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.session.PairingSessionRegistry
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I4 — the migration handshake gate wired into [PrepareUploadHandler] (integration-design §6.1). These
 * verify the SEAM behavior (gate runs before session creation, reject → 403 with reason, handshake
 * required when a validator is present, no session on reject). The env/account/version LOGIC itself is
 * tested in `:app`'s `MigrationHandshakeGateTest` (it needs EnvironmentHelper/FormatCompat).
 */
class PrepareUploadGateTest {

    private val info = DeviceInfo(alias = "Sender", fingerprint = "fp")

    private fun fileMeta(id: String) = FileMetadata(
        id = id, fileName = "$id.bin", size = 10L, fileType = "application/octet-stream", sha256 = null,
    )

    private val handshake = MigrationHandshake(
        sourceEnvironment = "test",
        sourceAccount = "u_a",
        sourceAppVersion = "1.0.0",
        sourceFormatVersion = "1.0",
    )

    private fun request(withHandshake: Boolean, pairToken: String? = null, size: Long = 10L) = PrepareUploadRequest(
        info = info,
        files = mapOf("f1" to fileMeta("f1").copy(size = size)),
        migration = if (withHandshake) handshake else null,
        pairToken = pairToken,
    )

    @Test
    fun `gate ACCEPT proceeds to session creation`() {
        val sm = SessionManager()
        val handler = PrepareUploadHandler(
            sm, PinPolicy(), requirePin = false,
            migrationValidator = { MigrationGateDecision.Accept },
        )
        val resp = handler.handle(request(withHandshake = true), "1.1.1.1", null)
        assertTrue(resp.sessionId.isNotBlank())
        assertEquals(resp.sessionId, sm.activeSessionId())
    }

    @Test
    fun `gate REJECT env-mismatch maps to 403 with the typed reason and NO session is created`() {
        val sm = SessionManager()
        val handler = PrepareUploadHandler(
            sm, PinPolicy(), requirePin = false,
            migrationValidator = { MigrationGateDecision.Reject(MigrationRejectReason.ENV_MISMATCH) },
        )
        val ex = assertThrows(ProtocolError.Forbidden::class.java) {
            handler.handle(request(withHandshake = true), "1.1.1.1", null)
        }
        assertEquals(403, ex.httpStatus)
        // The reason rides the 403 body via detailBody() so the sender can show the specific message.
        assertEquals(MigrationRejectReason.ENV_MISMATCH.wire, ex.detail)
        assertEquals(MigrationRejectReason.ENV_MISMATCH.wire, ex.detailBody())
        // The hard guarantee: a rejected handshake creates NO session → no upload can follow.
        assertNull(sm.activeSessionId())
    }

    @Test
    fun `gate REJECT account-mismatch maps to 403 with the typed reason`() {
        val sm = SessionManager()
        val handler = PrepareUploadHandler(
            sm, PinPolicy(), requirePin = false,
            migrationValidator = { MigrationGateDecision.Reject(MigrationRejectReason.ACCOUNT_MISMATCH) },
        )
        val ex = assertThrows(ProtocolError.Forbidden::class.java) {
            handler.handle(request(withHandshake = true), "1.1.1.1", null)
        }
        assertEquals(MigrationRejectReason.ACCOUNT_MISMATCH.wire, ex.detail)
        assertNull(sm.activeSessionId())
    }

    @Test
    fun `gate REJECT version-incompatible maps to 403 with the typed reason`() {
        val sm = SessionManager()
        val handler = PrepareUploadHandler(
            sm, PinPolicy(), requirePin = false,
            migrationValidator = { MigrationGateDecision.Reject(MigrationRejectReason.VERSION_INCOMPATIBLE) },
        )
        val ex = assertThrows(ProtocolError.Forbidden::class.java) {
            handler.handle(request(withHandshake = true), "1.1.1.1", null)
        }
        assertEquals(MigrationRejectReason.VERSION_INCOMPATIBLE.wire, ex.detail)
        assertNull(sm.activeSessionId())
    }

    @Test
    fun `a migration receiver REQUIRES the handshake — a request without one is rejected (gate cannot be skipped)`() {
        val sm = SessionManager()
        var validatorCalled = false
        val handler = PrepareUploadHandler(
            sm, PinPolicy(), requirePin = false,
            migrationValidator = { validatorCalled = true; MigrationGateDecision.Accept },
        )
        val ex = assertThrows(ProtocolError.Forbidden::class.java) {
            handler.handle(request(withHandshake = false), "1.1.1.1", null)
        }
        assertEquals(403, ex.httpStatus)
        // Validator is never even consulted — a missing handshake is rejected up front.
        assertTrue(!validatorCalled)
        assertNull(sm.activeSessionId())
    }

    @Test
    fun `with NO validator the generic file path ignores the handshake field (oracle-compat)`() {
        val sm = SessionManager()
        // No migrationValidator → generic file transfer / LocalSend oracle. A request that happens to
        // carry (or omit) a handshake is accepted exactly as before — the gate does not exist here.
        val handler = PrepareUploadHandler(sm, PinPolicy(), requirePin = false)
        val resp = handler.handle(request(withHandshake = true), "1.1.1.1", null)
        assertTrue(resp.sessionId.isNotBlank())
    }

    // ── Reverse pairing: the pending session replaces the PIN policy ─────────────────────────────

    @Test
    fun `a pairing session accepts a matching token and PIN without touching the PIN policy`() {
        val sm = SessionManager()
        val pinPolicy = PinPolicy()
        val issued = pinPolicy.issue()
        val registry = PairingSessionRegistry()
        registry.register(pin = SCANNED_PIN, transferKey = "k", pairToken = TOKEN)
        val handler = handler(sm, pinPolicy, registry)

        val resp = handler.handle(request(withHandshake = true, pairToken = TOKEN), "1.1.1.1", SCANNED_PIN)

        assertTrue(resp.sessionId.isNotBlank())
        // The pairing PIN is bound to the session, so the listener's own one-time PIN is untouched —
        // a five-minute confirmation hang can therefore never turn into a 401 here.
        assertEquals(issued, pinPolicy.current())
    }

    @Test
    fun `a pairToken with no pairing session is a stale generation and is refused`() {
        val sm = SessionManager()
        val handler = handler(sm, PinPolicy(), PairingSessionRegistry())

        val ex = assertThrows(ProtocolError.Forbidden::class.java) {
            handler.handle(request(withHandshake = true, pairToken = TOKEN), "1.1.1.1", SCANNED_PIN)
        }

        assertEquals(MigrationRejectReason.PAIR_TOKEN.wire, ex.detailBody())
        assertNull(sm.activeSessionId())
    }

    @Test
    fun `with no pairing session and no token the PIN path is unchanged`() {
        val sm = SessionManager()
        val pinPolicy = PinPolicy()
        val issued = pinPolicy.issue()
        val handler = PrepareUploadHandler(
            sm, pinPolicy, requirePin = true,
            migrationValidator = { MigrationGateDecision.Accept },
            pairingRegistry = PairingSessionRegistry(),
        )

        assertThrows(ProtocolError.Unauthorized::class.java) {
            handler.handle(request(withHandshake = true), "1.1.1.1", "000000")
        }
        val resp = handler.handle(request(withHandshake = true), "1.1.1.1", issued)
        assertTrue(resp.sessionId.isNotBlank())
    }

    // ── Storage precheck: after the gate, before the session ─────────────────────────────────────

    @Test
    fun `a gate reject wins over a full disk and the storage precheck is never consulted`() {
        val sm = SessionManager()
        var precheckCalled = false
        val handler = PrepareUploadHandler(
            sm, PinPolicy(), requirePin = false,
            migrationValidator = { MigrationGateDecision.Reject(MigrationRejectReason.ENV_MISMATCH) },
            storagePrecheck = { precheckCalled = true; false },
        )

        val ex = assertThrows(ProtocolError.Forbidden::class.java) {
            handler.handle(request(withHandshake = true), "1.1.1.1", null)
        }

        assertEquals(MigrationRejectReason.ENV_MISMATCH.wire, ex.detailBody())
        assertTrue("the gate's reason must not be masked by a storage answer", !precheckCalled)
    }

    @Test
    fun `no room refuses with insufficient-storage and creates no session`() {
        val sm = SessionManager()
        var seenBytes = -1L
        val handler = PrepareUploadHandler(
            sm, PinPolicy(), requirePin = false,
            migrationValidator = { MigrationGateDecision.Accept },
            storagePrecheck = { declared -> seenBytes = declared; false },
        )

        val ex = assertThrows(ProtocolError.Forbidden::class.java) {
            handler.handle(request(withHandshake = true, size = DECLARED_BYTES), "1.1.1.1", null)
        }

        assertEquals(MigrationRejectReason.INSUFFICIENT_STORAGE.wire, ex.detailBody())
        assertEquals(DECLARED_BYTES, seenBytes)
        assertNull(sm.activeSessionId())
    }

    @Test
    fun `room enough proceeds, and an unknown size reaches the precheck as zero`() {
        val sm = SessionManager()
        var seenBytes = -1L
        val handler = PrepareUploadHandler(
            sm, PinPolicy(), requirePin = false,
            migrationValidator = { MigrationGateDecision.Accept },
            storagePrecheck = { declared -> seenBytes = declared; true },
        )

        val resp = handler.handle(request(withHandshake = true, size = 0L), "1.1.1.1", null)

        assertTrue(resp.sessionId.isNotBlank())
        assertEquals(0L, seenBytes)
    }

    private fun handler(sm: SessionManager, pinPolicy: PinPolicy, registry: PairingSessionRegistry) =
        PrepareUploadHandler(
            sm, pinPolicy, requirePin = true,
            migrationValidator = { MigrationGateDecision.Accept },
            pairingRegistry = registry,
        )

    private companion object {
        /** The PIN the phone read off the computer's code, not one it issued. */
        const val SCANNED_PIN = "482913"
        const val TOKEN = "dG9rZW4"
        const val DECLARED_BYTES = 600L * 1024 * 1024
    }
}
