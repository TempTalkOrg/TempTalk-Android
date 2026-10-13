package com.difft.android.chat.translate

import com.difft.android.chat.translate.model.ModelState
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.TranslateTargetLanguage
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [AutoTranslateCoordinator] re-running a manual translation whose pack wait gave up, once the pack
 * turns ready while the page is still open. Fixture in [AutoTranslateCoordinatorTestBase].
 */
class AutoTranslateCoordinatorManualResumeTest : AutoTranslateCoordinatorTestBase() {

    /** First wait gives up (failed / stuck); any later one finds the pack ready. */
    private val waits = ConcurrentLinkedQueue(listOf(false))

    @Before
    fun gateThePack() {
        coEvery { modelStore.awaitReady(TranslateLang.ZH) } coAnswers { waits.poll() ?: true }
        every { modelStore.isReady(any<TranslateLang>()) } returns false
    }

    private suspend fun failManualOnThePack(id: String) = message(id).also { target ->
        startApplied()
        answeringSubmit()
        coordinator.onWindowAssembled(listOf(target))
        coordinator.translateManually(target, TranslateTargetLanguage.ZH)
        awaitOverlay("the model failure state") { it[id]?.translateStatus == TranslateStatus.FailedModel }
        assertEquals(0, submissions.size)
    }

    private fun packTurnsReady(vararg extra: Pair<ModelId, ModelState>) {
        every { modelStore.isReady(any<TranslateLang>()) } returns true
        modelState.value = mapOf(ModelId("zh") to ModelState.Ready, *extra)
    }

    @Test
    fun `a manual row that gave up on the pack translates once the pack turns ready`() = coordinatorTest {
        failManualOnThePack("m0")

        packTurnsReady()

        awaitOverlay("the resumed translation") { it["m0"]?.translateStatus == TranslateStatus.ShowCN }
        assertEquals("m0#ZH#manual", submissions.single().key)

        // The entry was consumed: a later state change does not submit the row again.
        packTurnsReady(ModelId("en") to ModelState.Ready)
        delay(SETTLE_MS)
        assertEquals(1, submissions.size)
    }

    @Test
    fun `a manual row turned off after giving up is not resurrected by the pack turning ready`() =
        coordinatorTest {
            val target = failManualOnThePack("m0")
            coordinator.translateOff(target)

            packTurnsReady()
            delay(SETTLE_MS)

            assertEquals(TranslateStatus.Invisible, coordinator.overlay.value.getValue("m0").translateStatus)
            assertEquals(0, submissions.size)
        }

    @Test
    fun `a manual row that gave up is not resumed after close`() = coordinatorTest {
        failManualOnThePack("m0")
        coordinator.close()

        packTurnsReady()
        delay(SETTLE_MS)

        assertEquals(0, submissions.size)
    }
}
