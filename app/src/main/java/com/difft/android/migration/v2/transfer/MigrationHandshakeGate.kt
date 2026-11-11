package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.p2p.engine.upload.MigrationGateDecision
import com.difft.android.p2p.engine.upload.MigrationHandshakeValidator
import com.difft.android.p2p.engine.upload.MigrationRejectReason

















class MigrationHandshakeGate(
    private val environmentHelper: EnvironmentHelper,
    private val loggingInAccount: String,
    private val supportedFormatVersion: String = FormatCompat.SUPPORTED_FORMAT_VERSION,
    private val credentialHandoff: Boolean = false,
    private val isFreshTarget: () -> Boolean = { true },
    private val handoffEnabled: () -> Boolean = { true },
    private val onAccept: (MigrationHandshake) -> Unit = {},
) : MigrationHandshakeValidator {

    override fun validate(handshake: MigrationHandshake): MigrationGateDecision {
        
        if (handshake.sourceEnvironment != MigrationEnvironment.wireName(environmentHelper)) {
            L.w {
                "[MigrationV2][P2P][Gate] REJECT env mismatch " +
                    "(source!=this) reason=${MigrationRejectReason.ENV_MISMATCH.wire}"
            }
            return MigrationGateDecision.Reject(MigrationRejectReason.ENV_MISMATCH)
        }

        
        
        
        val senderClaimsHandoff = handshake.credentialHandoff == true
        if (credentialHandoff != senderClaimsHandoff) {
            L.w {
                "[MigrationV2][P2P][Gate] REJECT handoff mode disagreement receiver=$credentialHandoff " +
                    "sender=$senderClaimsHandoff reason=${MigrationRejectReason.HANDOFF_UNSUPPORTED.wire}"
            }
            return MigrationGateDecision.Reject(MigrationRejectReason.HANDOFF_UNSUPPORTED)
        }
        if (credentialHandoff) {
            if (!handoffEnabled()) {
                L.w { "[MigrationV2][P2P][Gate] REJECT handoff disabled reason=${MigrationRejectReason.HANDOFF_DISABLED.wire}" }
                return MigrationGateDecision.Reject(MigrationRejectReason.HANDOFF_DISABLED)
            }
            if (!isFreshTarget()) {
                L.w { "[MigrationV2][P2P][Gate] REJECT target not empty reason=${MigrationRejectReason.TARGET_NOT_EMPTY.wire}" }
                return MigrationGateDecision.Reject(MigrationRejectReason.TARGET_NOT_EMPTY)
            }
        }

        
        if (!credentialHandoff) {
            if (!accountMatches(handshake.sourceAccount, loggingInAccount)) {
                L.w { "[MigrationV2][P2P][Gate] REJECT account mismatch reason=${MigrationRejectReason.ACCOUNT_MISMATCH.wire}" }
                return MigrationGateDecision.Reject(MigrationRejectReason.ACCOUNT_MISMATCH)
            }
        } else {
            
            
            if (loggingInAccount.isNotBlank() || handshake.sourceAccount.isBlank()) {
                L.w { "[MigrationV2][P2P][Gate] REJECT handoff account shape reason=${MigrationRejectReason.ACCOUNT_MISMATCH.wire}" }
                return MigrationGateDecision.Reject(MigrationRejectReason.ACCOUNT_MISMATCH)
            }
        }

        
        if (!FormatCompat.isCompatible(handshake.sourceFormatVersion)) {
            L.w {
                "[MigrationV2][P2P][Gate] REJECT version incompatible " +
                    "sourceFmt=${handshake.sourceFormatVersion} supported=$supportedFormatVersion " +
                    "reason=${MigrationRejectReason.VERSION_INCOMPATIBLE.wire}"
            }
            return MigrationGateDecision.Reject(MigrationRejectReason.VERSION_INCOMPATIBLE)
        }

        L.i { "[MigrationV2][P2P][Gate] ACCEPT (env+account+version compatible) handoff=$credentialHandoff" }
        onAccept(handshake)
        return MigrationGateDecision.Accept
    }

    
    private fun accountMatches(sourceAccount: String, currentAccount: String): Boolean =
        sourceAccount.isNotBlank() &&
            currentAccount.isNotBlank() &&
            sourceAccount.trim() == currentAccount.trim()
}
