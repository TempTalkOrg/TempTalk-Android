package com.difft.android.migration.v2.login

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.migration.MigrationImportLauncher
import com.difft.android.base.migration.MigrationImportOutcome
import com.difft.android.base.migration.PendingRestoreSignIn
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.migration.model.RestorePhase
import com.difft.android.migration.v2.restore.RestoreFlagStore
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A5 (design §6) — [MigrationImportLauncherImpl] bridge: the resume-into-login probe + result parsing
 * + the P2P offer/intent seams.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationImportLauncherImplTest {

    private lateinit var context: Context
    private lateinit var userManager: UserManager
    private lateinit var launcher: MigrationImportLauncherImpl
    private lateinit var flagStore: RestoreFlagStore

    private companion object {
        const val ACCOUNT = "+12025550100"
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        userManager = mockk(relaxed = true)
        launcher = MigrationImportLauncherImpl(context, userManager)
        flagStore = RestoreFlagStore(context).also { it.clear() }
    }

    @After
    fun teardown() {
        flagStore.clear()
    }

    // ── resume-into-login probe (the SIGNING_IN skip-prompt path, design §4) ────────────────────

    @Test
    fun `no restore flag means no pending sign-in`() {
        assertNull(launcher.pendingRestoreSignIn())
    }

    @Test
    fun `importing phase is not a pending sign-in`() {
        flagStore.begin("backup.ttmig", ACCOUNT) // writes phase=IMPORTING
        assertNull(launcher.pendingRestoreSignIn())
    }

    @Test
    fun `signing-in phase is a pending sign-in bound to the account with the installed identity`() {
        flagStore.begin("backup.ttmig", ACCOUNT)
        flagStore.advance(RestorePhase.SIGNING_IN)
        assertEquals(PendingRestoreSignIn(ACCOUNT, useInstalledIdentity = true), launcher.pendingRestoreSignIn())
    }

    @Test
    fun `a recorded identity publish failure switches the pending sign-in to a fresh identity`() {
        flagStore.begin("backup.ttmig", ACCOUNT)
        flagStore.advance(RestorePhase.SIGNING_IN)

        launcher.markRestoreIdentityPublishFailed()

        // Still pending for the same account — the data is on disk — but the identity is not retried.
        assertEquals(PendingRestoreSignIn(ACCOUNT, useInstalledIdentity = false), launcher.pendingRestoreSignIn())
    }

    @Test
    fun `a signing-in flag with no account binds nobody`() {
        // A flag without the account key (older writer) can neither continue a restore for anyone nor
        // block anyone — it must not resurrect the account-agnostic trap.
        File(context.filesDir, RestoreFlagStore.FLAG_FILE_NAME).writeText(
            """{"state":"RESTORING","startedAt":1,"sourceBackupUri":"x","phase":"SIGNING_IN"}"""
        )
        assertNull(launcher.pendingRestoreSignIn())
    }

    @Test
    fun `marking with no flag is a no-op`() {
        launcher.markRestoreIdentityPublishFailed()
        assertNull(launcher.pendingRestoreSignIn())
        assertFalse(flagStore.exists())
    }

    // ── T9: a successful restore sign-in ends the pending sign-in and arms the one-shot success page ──

    @Test
    fun `marking the restore sign-in complete ends the pending sign-in and arms the success page once`() {
        flagStore.begin("backup.ttmig", ACCOUNT)
        flagStore.advance(RestorePhase.SIGNING_IN)
        assertNotNull(launcher.pendingRestoreSignIn())

        // The VerifyCodeActivity success handler, just before restartApp().
        launcher.markRestoreSignInComplete()

        // No login is pulled back onto the restore path any more…
        assertNull(launcher.pendingRestoreSignIn())
        // …and the first cold start shows the success page exactly once.
        assertTrue(flagStore.consumeCompleted(), "first launch after the sign-in shows the success page")
        assertFalse(flagStore.consumeCompleted(), "a second launch does not")
        assertFalse(flagStore.exists(), "consuming the flag removes it")
    }

    @Test
    fun `marking complete with no flag on disk still arms the success page once`() {
        // A restore-path sign-in without a flag on disk cannot happen today, but the write must not
        // throw, and the result must still be the one-shot page — never a lingering pending state.
        launcher.markRestoreSignInComplete()
        assertNull(launcher.pendingRestoreSignIn())
        assertTrue(flagStore.consumeCompleted())
    }

    // ── unified net-install signal: isFreshInstallTarget (account-empty discriminator) ──────────

    @Test
    fun `empty account means fresh install target`() {
        // Fresh install / post-uninstall / post-full-logout → no account established → fresh.
        every { userManager.getUserData() } returns UserData(account = null)
        assertTrue(launcher.isFreshInstallTarget())
        every { userManager.getUserData() } returns UserData(account = "")
        assertTrue(launcher.isFreshInstallTarget())
        // No UserData at all (pre-warm-up) is also "no account established" → fresh.
        every { userManager.getUserData() } returns null
        assertTrue(launcher.isFreshInstallTarget())
    }

    @Test
    fun `present account means not a fresh install target`() {
        // An account was established here (incl. after a passive device-kick, which PRESERVES
        // account) → NOT fresh → import blocked / no prompt.
        every { userManager.getUserData() } returns UserData(account = "+12025550100")
        assertFalse(launcher.isFreshInstallTarget())
    }

    @Test
    fun `account read error is fail-safe to not-fresh`() {
        // A read throw must resolve to NOT fresh so a transient failure never wrongly allows import.
        every { userManager.getUserData() } throws RuntimeException("storage gone")
        assertFalse(launcher.isFreshInstallTarget())
    }

    // ── result parsing — fail-safe to Cancelled for anything but RESULT_RESTORED ────────────────

    @Test
    fun `result restored parses to Restored`() {
        assertEquals(
            MigrationImportOutcome.Restored,
            launcher.parseResult(MigrationImportLauncher.RESULT_RESTORED, null),
        )
    }

    @Test
    fun `result skipped parses to Cancelled`() {
        assertEquals(
            MigrationImportOutcome.Cancelled,
            launcher.parseResult(MigrationImportLauncher.RESULT_SKIPPED, null),
        )
    }

    @Test
    fun `unknown result parses to Cancelled`() {
        assertEquals(MigrationImportOutcome.Cancelled, launcher.parseResult(0, null))
        assertEquals(MigrationImportOutcome.Cancelled, launcher.parseResult(-1, null))
    }

    // ── I4 P2P bridge: intent building + offered-probe ──────────────────────────────────────────

    @Test
    fun `P2P receive intent targets the receive Activity with the account extra`() {
        val intent = launcher.createP2pReceiveIntent(context, "+12025550100")
        assertEquals(
            "com.difft.android.migration.v2.transfer.login.MigrationP2pReceiveActivity",
            intent.component?.className,
        )
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals("+12025550100", intent.getStringExtra("extra_account"))
    }

    // ── P2P offered: NOT gated on the network ───────────────────────────────────────────────────
    // The offer used to require a usable LAN, which hid the method from anyone who had simply not
    // joined a Wi-Fi yet — and on a P2P-only platform that reads as "migration is unavailable" with no
    // reason given. The probe was also wrong in both directions (a VPN tunnel or a carrier-NAT mobile
    // address looks like a LAN). Reachability is now proven at pairing, which reports typed failures.

    @Test
    fun `P2P is offered regardless of the host network`() {
        // Deterministic: no dependency on whether the test host happens to have a LAN up.
        assertTrue(launcher.p2pOffered())
        assertTrue(launcher.isP2pMigrationOffered(context))
    }
}
