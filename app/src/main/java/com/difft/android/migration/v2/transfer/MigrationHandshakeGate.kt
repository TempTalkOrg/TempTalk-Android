package com.difft.android.migration.v2.transfer

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.p2p.engine.upload.MigrationGateDecision
import com.difft.android.p2p.engine.upload.MigrationHandshakeValidator
import com.difft.android.p2p.engine.upload.MigrationRejectReason

/**
 * Pre-transfer gate the receiver runs at `prepare-upload`, before accepting the session and before
 * any container byte, so an incompatible source is rejected at no cost.
 *
 * Four checks, in this order: environment isolation (test and prod never mix), handoff-mode
 * agreement, account match — replaced by account adoption on the same-device handoff path, which has
 * no local account — and container format compatibility. The handoff check must stay ahead of the
 * account check because it changes the account rule. Never throws: the [MigrationHandshakeValidator]
 * contract would turn a throw into an opaque 500. Never logs the raw environment or account values.
 *
 * @param loggingInAccount the account just verified on this device; blank on the handoff path.
 * @param isFreshTarget net-install probe, evaluated at gate time rather than mid-transfer.
 * @param handoffEnabled whether this receiver can run the credential handoff at all.
 * @param onAccept adopts the sender's declared account on the handoff path; the adopted value is
 *   re-confirmed against the HMAC'd manifest before any record is staged.
 */
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
        // 1) Environment isolation (test↔test / prod↔prod), compared by wire name.
        if (handshake.sourceEnvironment != MigrationEnvironment.wireName(environmentHelper)) {
            L.w {
                "[MigrationV2][P2P][Gate] REJECT env mismatch " +
                    "(source!=this) reason=${MigrationRejectReason.ENV_MISMATCH.wire}"
            }
            return MigrationGateDecision.Reject(MigrationRejectReason.ENV_MISMATCH)
        }

        // 2) Handoff-mode agreement, ahead of the account check because handoff changes the account
        // rule. A disagreement is a hard reject, not a downgrade: otherwise the sender hands over its
        // credential, is told 200, and wipes while the receiver never installs it.
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

        // 3) Account — match on the normal path, adoption on the handoff path. Raw values not logged.
        if (!credentialHandoff) {
            if (!accountMatches(handshake.sourceAccount, loggingInAccount)) {
                L.w { "[MigrationV2][P2P][Gate] REJECT account mismatch reason=${MigrationRejectReason.ACCOUNT_MISMATCH.wire}" }
                return MigrationGateDecision.Reject(MigrationRejectReason.ACCOUNT_MISMATCH)
            }
        } else {
            // A handoff receiver has no account to match against — carrying one here means the
            // receiver was wired wrong, and a blank source account would leave nothing to adopt.
            if (loggingInAccount.isNotBlank() || handshake.sourceAccount.isBlank()) {
                L.w { "[MigrationV2][P2P][Gate] REJECT handoff account shape reason=${MigrationRejectReason.ACCOUNT_MISMATCH.wire}" }
                return MigrationGateDecision.Reject(MigrationRejectReason.ACCOUNT_MISMATCH)
            }
        }

        // 4) Format compatibility — cross-major reject, the same FormatCompat rule the reader applies.
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

    /** Both non-blank and equal after trim. Mirrors `RestoreOrchestrator.accountMatches`. */
    private fun accountMatches(sourceAccount: String, currentAccount: String): Boolean =
        sourceAccount.isNotBlank() &&
            currentAccount.isNotBlank() &&
            sourceAccount.trim() == currentAccount.trim()
}
