package com.difft.android.p2p.engine.upload

import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.p2p.engine.model.PrepareUploadRequest
import com.difft.android.p2p.engine.model.ProtocolError
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

    private fun request(withHandshake: Boolean) = PrepareUploadRequest(
        info = info,
        files = mapOf("f1" to fileMeta("f1")),
        migration = if (withHandshake) handshake else null,
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
}
