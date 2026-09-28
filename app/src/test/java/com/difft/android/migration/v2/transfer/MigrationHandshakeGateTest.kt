package com.difft.android.migration.v2.transfer

import com.difft.android.base.utils.AppScheme
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.p2p.engine.qr.QrPayloadV2Codec
import com.difft.android.p2p.engine.upload.MigrationGateDecision
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SECURITY-CORE tests for the I4 environment + account + version isolation gate (integration-design
 * §6.1). The headline requirement: a test/TTDev backup MUST be rejected on a prod/TTOnline device AND
 * vice-versa, BEFORE any byte flows. Pure-JVM (a fake [EnvironmentHelper]); no Android needed.
 */
class MigrationHandshakeGateTest {

    /** Minimal fake EnvironmentHelper: `currentEnvironment` is fixed; `isThatEnvironment` is equality. */
    private fun envHelper(current: String) = object : EnvironmentHelper {
        override val currentEnvironment: String = current
        override val ENVIRONMENT_DEVELOPMENT: String = ENV_TEST
        override val ENVIRONMENT_ONLINE: String = ENV_PROD
        override fun isThatEnvironment(environment: String): Boolean = currentEnvironment == environment
        override fun isGoogleChannel(): Boolean = false
        override fun isInsiderChannel(): Boolean = false
        override fun getChannelName(): String = "test"
        override fun getScheme(): String = AppScheme.SCHEME_CHATIVE
    }

    private fun handshake(
        env: String,
        account: String = ACCOUNT,
        formatVersion: String = FormatCompat.SUPPORTED_FORMAT_VERSION,
    ) = MigrationHandshake(
        sourceEnvironment = env,
        sourceAccount = account,
        sourceAppVersion = "1.0.0",
        sourceFormatVersion = formatVersion,
    )

    private fun gate(deviceEnv: String, account: String = ACCOUNT) =
        MigrationHandshakeGate(environmentHelper = envHelper(deviceEnv), loggingInAccount = account)

    // ── environment isolation (the security red line) ──────────────────────────────────────────

    @Test
    fun `same environment (test on test) ACCEPTS`() {
        val decision = gate(deviceEnv = ENV_TEST).validate(handshake(env = WIRE_TEST))
        assertEquals(MigrationGateDecision.Accept, decision)
    }

    @Test
    fun `same environment (prod on prod) ACCEPTS`() {
        val decision = gate(deviceEnv = ENV_PROD).validate(handshake(env = WIRE_PROD))
        assertEquals(MigrationGateDecision.Accept, decision)
    }

    @Test
    fun `a TEST backup on a PROD device is REJECTED with ENV_MISMATCH`() {
        // The memory'd hard gate, direction 1: test source → prod device.
        val decision = gate(deviceEnv = ENV_PROD).validate(handshake(env = WIRE_TEST))
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.ENV_MISMATCH), decision)
    }

    @Test
    fun `a PROD backup on a TEST device is REJECTED with ENV_MISMATCH`() {
        // The memory'd hard gate, direction 2: prod source → test device.
        val decision = gate(deviceEnv = ENV_TEST).validate(handshake(env = WIRE_PROD))
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.ENV_MISMATCH), decision)
    }

    @Test
    fun `environment is checked FIRST — an env mismatch rejects even if account also mismatches`() {
        // Env is the most important reject; it short-circuits before account/version.
        val decision = gate(deviceEnv = ENV_PROD, account = "u_device")
            .validate(handshake(env = WIRE_TEST, account = "u_other"))
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.ENV_MISMATCH), decision)
    }

    // ── account match ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `account mismatch (same env) is REJECTED with ACCOUNT_MISMATCH`() {
        val decision = gate(deviceEnv = ENV_TEST, account = "u_device")
            .validate(handshake(env = WIRE_TEST, account = "u_other"))
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.ACCOUNT_MISMATCH), decision)
    }

    @Test
    fun `account match is trimmed`() {
        val decision = gate(deviceEnv = ENV_TEST, account = "u_a")
            .validate(handshake(env = WIRE_TEST, account = "  u_a  "))
        assertEquals(MigrationGateDecision.Accept, decision)
    }

    @Test
    fun `a blank source account is REJECTED with ACCOUNT_MISMATCH`() {
        val decision = gate(deviceEnv = ENV_TEST, account = "u_a")
            .validate(handshake(env = WIRE_TEST, account = "   "))
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.ACCOUNT_MISMATCH), decision)
    }

    // ── version / format compat ──────────────────────────────────────────────────────────────────

    @Test
    fun `a cross-MAJOR format version is REJECTED with VERSION_INCOMPATIBLE`() {
        val crossMajor = bumpMajor(FormatCompat.SUPPORTED_FORMAT_VERSION)
        val decision = gate(deviceEnv = ENV_TEST)
            .validate(handshake(env = WIRE_TEST, formatVersion = crossMajor))
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.VERSION_INCOMPATIBLE), decision)
    }

    @Test
    fun `an unparseable format version is REJECTED with VERSION_INCOMPATIBLE`() {
        val decision = gate(deviceEnv = ENV_TEST)
            .validate(handshake(env = WIRE_TEST, formatVersion = "not-a-version"))
        assertEquals(MigrationGateDecision.Reject(MigrationRejectReason.VERSION_INCOMPATIBLE), decision)
    }

    @Test
    fun `same-MAJOR newer-MINOR is ACCEPTED (additive-only passthrough)`() {
        // Same MAJOR, a higher MINOR — accepted, matching FormatCompat's rule.
        val sameMajorNewerMinor = bumpMinor(FormatCompat.SUPPORTED_FORMAT_VERSION)
        val decision = gate(deviceEnv = ENV_TEST)
            .validate(handshake(env = WIRE_TEST, formatVersion = sameMajorNewerMinor))
        assertEquals(MigrationGateDecision.Accept, decision)
    }

    @Test
    fun `the gate never throws — every reject is a typed decision`() {
        // Pathological input (blank everything) returns a Reject, not an exception (handler contract).
        val decision = gate(deviceEnv = ENV_TEST).validate(handshake(env = "", account = "", formatVersion = ""))
        assertTrue(decision is MigrationGateDecision.Reject)
    }

    private fun bumpMajor(v: String): String {
        val major = v.substringBefore('.').toInt()
        val minor = v.substringAfter('.', "0")
        return "${major + 1}.$minor"
    }

    private fun bumpMinor(v: String): String {
        val major = v.substringBefore('.')
        val minor = v.substringAfter('.', "0").toIntOrNull() ?: 0
        return "$major.${minor + 1}"
    }

    companion object {
        /** This build's own environment names, as `BuildConfig.ENVIRONMENT` spells them. */
        private const val ENV_TEST = "EnvironmentDevelopment"
        private const val ENV_PROD = "EnvironmentOnline"

        /** The names that travel in the handshake — the same on every platform. */
        private const val WIRE_TEST = QrPayloadV2Codec.Environment.TEST
        private const val WIRE_PROD = QrPayloadV2Codec.Environment.PROD
        private const val ACCOUNT = "u_a"
    }
}
