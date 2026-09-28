package com.difft.android.p2p.engine.upload

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.model.PrepareUploadRequest
import com.difft.android.p2p.engine.model.PrepareUploadResponse
import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.model.ReceiverCaps
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager

/**
 * Engine handler for `POST /api/localsend/v2/prepare-upload?pin=`.
 *
 * Transport-agnostic: takes the already-parsed [PrepareUploadRequest], the source IP, and the
 * optional PIN query param; returns a [PrepareUploadResponse] or throws a [ProtocolError]
 * mapped to the correct HTTP status. The Android server adapter does the socket/JSON work.
 *
 * The response is also where this receiver declares what it can do ([caps]) and hands the sender
 * its session token: both are things the sender needs before it sends the body and after the
 * upload connection is gone, and this is the one exchange that is guaranteed to happen in between.
 *
 * @param requirePin true on the QR pairing path, where a valid one-time PIN is mandatory (401 when
 *   missing or wrong); false skips the PIN check.
 * @param migrationValidator the receiver-side migration pre-transfer gate — environment isolation,
 *   account match and format/version compatibility, checked after the PIN and before the session is
 *   created. Non-null only on the migration receiver path. When present it requires the request to
 *   carry a handshake, so the environment gate can never be silently skipped.
 * @param caps what this receiver advertises; see [ReceiverCaps]. A peer reads capabilities only from
 *   this list, never from a version number.
 */
class PrepareUploadHandler(
    private val sessionManager: SessionManager,
    private val pinPolicy: PinPolicy,
    private val requirePin: Boolean,
    private val migrationValidator: MigrationHandshakeValidator? = null,
    private val caps: List<String> = listOf(ReceiverCaps.STATUS_V1, ReceiverCaps.BEAT_V1),
) {
    fun handle(request: PrepareUploadRequest, sourceIp: String, pin: String?): PrepareUploadResponse {
        if (request.files.isEmpty()) throw ProtocolError.NoContent

        // Each declared file must carry an id consistent with its map key.
        request.files.forEach { (fileId, meta) ->
            if (meta.id != fileId) {
                throw ProtocolError.BadRequest("file id '$meta.id' != map key '$fileId'")
            }
        }

        if (requirePin && !pinPolicy.validateAndConsume(pin)) {
            // Source IP only — never the PIN candidate. Repeated lines from one IP are the
            // brute-force signal; the PIN self-invalidates after PinPolicy.MAX_PIN_ATTEMPTS.
            L.w { "[P2P][Receiver] pin rejected from=$sourceIp" }
            throw ProtocolError.Unauthorized
        }

        // Migration gate — runs only when a validator is wired. The authoritative env/account/version
        // check, before any session is created and so before any container byte is accepted. The
        // handshake is required here, so a plain request cannot bypass the gate.
        migrationValidator?.let { validator ->
            val handshake = request.migration
                ?: throw ProtocolError.Forbidden("migration handshake required")
            when (val decision = validator.validate(handshake)) {
                is MigrationGateDecision.Accept ->
                    L.i { "[P2P][Migration] handshake gate ACCEPT" }
                is MigrationGateDecision.Reject -> {
                    // Log the typed reason only, never raw env/account.
                    L.w { "[P2P][Migration] handshake gate REJECT reason=${decision.reason.wire}" }
                    throw ProtocolError.Forbidden(decision.reason.wire)
                }
            }
        }

        val prepared = sessionManager.createSession(sourceIp, request.files)
        return PrepareUploadResponse(
            sessionId = prepared.sessionId,
            files = prepared.slots.mapValues { (_, slot) -> slot.token },
            sessionToken = prepared.sessionToken,
            caps = caps,
        )
    }
}
