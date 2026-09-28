package com.difft.android.migration.v2.transfer

import com.difft.android.base.utils.AppScheme
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.p2p.engine.qr.QrPayloadV2Codec
import com.difft.android.p2p.engine.upload.MigrationGateDecision
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The same-device no-login handoff branch of the pre-transfer gate.
 *
 * The safety-critical case is the mode disagreement: an OLD app that hands its credential over to a
 * receiver which will never install it would be told "200" and wipe, stranding the user signed out
 * on one app and asked for a verification code on the other. Both directions of that disagreement
 * are rejected, before any byte, and `onAccept` never fires on a reject.
 */
class MigrationHandshakeGateHandoffTest {

    private fun envHelper(current: String) = object : EnvironmentHelper {
        override val currentEnvironment: String = current
        override val ENVIRONMENT_DEVELOPMENT: String = ENV
        override val ENVIRONMENT_ONLINE: String = "prod"
        override fun isThatEnvironment(environment: String): Boolean = currentEnvironment == environment
        override fun isGoogleChannel(): Boolean = false
        override fun isInsiderChannel(): Boolean = false
        override fun getChannelName(): String = "test"
        override fun getScheme(): String = AppScheme.SCHEME_CHATIVE
    }

    private fun handshake(
        env: String = QrPayloadV2Codec.Environment.TEST,
        account: String = ACCOUNT,
        credentialHandoff: Boolean? = true,
    ) = MigrationHandshake(
        sourceEnvironment = env,
        sourceAccount = account,
        sourceAppVersion = "1.0.0",
        sourceFormatVersion = FormatCompat.SUPPORTED_FORMAT_VERSION,
        credentialHandoff = credentialHandoff,
    )

    private var accepted: MigrationHandshake? = null

    private fun handoffGate(
        loggingInAccount: String = "",
        isFreshTarget: Boolean = true,
        handoffEnabled: Boolean = true,
    ) = MigrationHandshakeGate(
        environmentHelper = envHelper(ENV),
        loggingInAccount = loggingInAccount,
        credentialHandoff = true,
        isFreshTarget = { isFreshTarget },
        handoffEnabled = { handoffEnabled },
        onAccept = { accepted = it },
    )

    @Test
    fun `a matching handoff handshake is accepted and publishes the adopted account`() {
        val decision = handoffGate().validate(handshake())
        assertEquals(MigrationGateDecision.Accept, decision)
        assertEquals(ACCOUNT, accepted?.sourceAccount)
    }

    @Test
    fun `a handoff sender on a NON-handoff receiver is rejected — the strand-the-user guard`() {
        val gate = MigrationHandshakeGate(
            environmentHelper = envHelper(ENV),
            loggingInAccount = ACCOUNT,
            onAccept = { accepted = it },
        )
        val decision = gate.validate(handshake(credentialHandoff = true))
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.HANDOFF_UNSUPPORTED), decision)
        assertNull(accepted)
    }

    @Test
    fun `a handoff receiver driven by a credential-free sender is rejected`() {
        val decision = handoffGate().validate(handshake(credentialHandoff = null))
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.HANDOFF_UNSUPPORTED), decision)
        assertNull(accepted)
    }

    @Test
    fun `a receiver that cannot run the handoff rejects with handoff-disabled`() {
        val decision = handoffGate(handoffEnabled = false).validate(handshake())
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.HANDOFF_DISABLED), decision)
        assertNull(accepted)
    }

    @Test
    fun `a receiver that already has an account is rejected before any byte`() {
        val decision = handoffGate(isFreshTarget = false).validate(handshake())
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.TARGET_NOT_EMPTY), decision)
        assertNull(accepted)
    }

    @Test
    fun `a handoff receiver wired with a logging-in account is a wiring bug and is rejected`() {
        val decision = handoffGate(loggingInAccount = "u_wired_wrong").validate(handshake())
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.ACCOUNT_MISMATCH), decision)
        assertNull(accepted)
    }

    @Test
    fun `a blank source account leaves nothing to adopt and is rejected`() {
        val decision = handoffGate().validate(handshake(account = ""))
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.ACCOUNT_MISMATCH), decision)
        assertNull(accepted)
    }

    @Test
    fun `environment isolation still comes first, even in handoff mode`() {
        val decision = handoffGate().validate(handshake(env = QrPayloadV2Codec.Environment.PROD))
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.ENV_MISMATCH), decision)
        assertNull(accepted)
    }

    @Test
    fun `a non-handoff transfer is unaffected by the new branch`() {
        val gate = MigrationHandshakeGate(
            environmentHelper = envHelper(ENV),
            loggingInAccount = ACCOUNT,
            onAccept = { accepted = it },
        )
        val decision = gate.validate(handshake(credentialHandoff = null))
        assertEquals(MigrationGateDecision.Accept, decision)
        assertEquals(ACCOUNT, accepted?.sourceAccount)
    }

    private companion object {
        const val ENV = "test"
        const val ACCOUNT = "+12025550100"
    }
}
