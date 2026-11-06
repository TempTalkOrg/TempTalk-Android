package com.difft.android.migration.v2.handoff

import android.content.Context
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserData
import com.difft.android.migration.v2.pause.SenderVerdictSettlement
import com.difft.android.migration.v2.pause.SettleResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The handoff id is the only key a handoff's message pause can be settled by (#1382), so a peer that
 * adopted nothing clears it only once the settlement actually resolved.
 */
class MigrationHandoffReconcilerTest {

    private var userData = UserData(baseAuth = "auth", migrationHandoffId = HANDOFF_ID)

    private val context: Context = mockk {
        every { packageName } returns PeerApps.LEGACY_FAMILY
    }
    private val userManager: StorageBoundUserManager = mockk(relaxed = true) {
        every { getUserData() } answers { userData }
        every { update(any(), any()) } answers {
            val block = secondArg<UserData.() -> Unit>()
            userData = userData.copy().apply(block)
        }
    }
    private val statusClient: MigrationStatusClient = mockk {
        coEvery { queryHandoff(HANDOFF_ID) } returns HandoffResult.UNKNOWN
    }
    private val completer: MigrationHandoffCompleter = mockk(relaxed = true)
    private val settlement: SenderVerdictSettlement = mockk()

    private val reconciler = MigrationHandoffReconciler(context, userManager, statusClient, completer, settlement)

    private fun settleReturns(vararg results: SettleResult) {
        coEvery { settlement.settle(HANDOFF_ID, null, MigrationPauseOutcome.FAILED, any()) } returnsMany results.toList()
    }

    @Test
    fun `a failed pause write keeps the handoff id so a later reconcile settles it`() = runTest {
        settleReturns(SettleResult.WRITE_FAILED, SettleResult.APPLIED)

        assertEquals(MigrationHandoffReconciler.Outcome.UNKNOWN, reconciler.onSendFailure())
        assertEquals(HANDOFF_ID, userData.migrationHandoffId, "the recovery key survives a failed settlement")

        assertEquals(MigrationHandoffReconciler.Outcome.NOT_MIGRATED, reconciler.onScreenResumed())
        assertEquals("", userData.migrationHandoffId)
        coVerify(exactly = 2) { settlement.settle(HANDOFF_ID, null, MigrationPauseOutcome.FAILED, any()) }
    }

    @Test
    fun `an applied settlement clears the handoff id`() = runTest {
        settleReturns(SettleResult.APPLIED)

        assertEquals(MigrationHandoffReconciler.Outcome.NOT_MIGRATED, reconciler.onColdStart())
        assertEquals("", userData.migrationHandoffId)
    }

    @Test
    fun `a skipped settlement clears the handoff id, since the pause is not this handoff's`() = runTest {
        settleReturns(SettleResult.SKIPPED)

        assertEquals(MigrationHandoffReconciler.Outcome.NOT_MIGRATED, reconciler.onColdStart())
        assertEquals("", userData.migrationHandoffId)
    }

    private companion object {
        const val HANDOFF_ID = "handoff-1"
    }
}
