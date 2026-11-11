package com.difft.android.p2p.engine.upload

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.model.PrepareUploadRequest
import com.difft.android.p2p.engine.model.PrepareUploadResponse
import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.model.ReceiverCaps
import com.difft.android.p2p.engine.session.PairingSessionRegistry
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager



























class PrepareUploadHandler(
    private val sessionManager: SessionManager,
    private val pinPolicy: PinPolicy,
    private val requirePin: Boolean,
    private val migrationValidator: MigrationHandshakeValidator? = null,
    private val caps: List<String> = ReceiverCaps.BASE,
    private val pairingRegistry: PairingSessionRegistry? = null,
    private val storagePrecheck: StoragePrecheck? = null,
) {
    fun handle(request: PrepareUploadRequest, sourceIp: String, pin: String?): PrepareUploadResponse {
        if (request.files.isEmpty()) throw ProtocolError.NoContent

        
        request.files.forEach { (fileId, meta) ->
            if (meta.id != fileId) {
                throw ProtocolError.BadRequest("file id '$meta.id' != map key '$fileId'")
            }
        }

        authorizeCredentials(request, sourceIp, pin)

        
        
        
        migrationValidator?.let { validator ->
            val handshake = request.migration
                ?: throw ProtocolError.Forbidden("migration handshake required")
            when (val decision = validator.validate(handshake)) {
                is MigrationGateDecision.Accept ->
                    L.i { "[P2P][Migration] handshake gate ACCEPT" }
                is MigrationGateDecision.Reject -> {
                    
                    L.w { "[P2P][Migration] handshake gate REJECT reason=${decision.reason.wire}" }
                    throw ProtocolError.Forbidden(decision.reason.wire)
                }
            }
        }

        
        
        storagePrecheck?.let { precheck ->
            val declaredBytes = request.files.values.sumOf { it.size.coerceAtLeast(0L) }
            if (!precheck.hasRoomFor(declaredBytes)) {
                L.w { "[P2P][Receiver] prepare-upload refused — no room for declaredBytes=$declaredBytes" }
                throw ProtocolError.Forbidden(MigrationRejectReason.INSUFFICIENT_STORAGE.wire)
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

    





    private fun authorizeCredentials(request: PrepareUploadRequest, sourceIp: String, pin: String?) {
        val registry = pairingRegistry?.takeIf { it.isActive() }
        if (registry != null) {
            when (registry.authorizePrepareUpload(request.pairToken, pin)) {
                PairingSessionRegistry.Decision.Accept -> Unit
                PairingSessionRegistry.Decision.RejectPairToken -> {
                    L.w { "[P2P][Receiver] prepare-upload rejected from=$sourceIp reason=${MigrationRejectReason.PAIR_TOKEN.wire}" }
                    throw ProtocolError.Forbidden(MigrationRejectReason.PAIR_TOKEN.wire)
                }
                PairingSessionRegistry.Decision.RejectPin -> {
                    L.w { "[P2P][Receiver] pin rejected from=$sourceIp" }
                    throw ProtocolError.Unauthorized
                }
            }
            return
        }
        if (request.pairToken != null) {
            
            L.w { "[P2P][Receiver] prepare-upload rejected from=$sourceIp reason=${MigrationRejectReason.PAIR_TOKEN.wire}" }
            throw ProtocolError.Forbidden(MigrationRejectReason.PAIR_TOKEN.wire)
        }
        if (requirePin && !pinPolicy.validateAndConsume(pin)) {
            
            
            L.w { "[P2P][Receiver] pin rejected from=$sourceIp" }
            throw ProtocolError.Unauthorized
        }
    }
}
