package com.difft.android.chat.translate

import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.TranslateTargetLanguage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [AutoTranslateCoordinator] manual attempts: with two overlapping attempts on one row only the
 * latest one may write the row's outcome, and a same-language manual result is shown and persisted
 * like any other translation. Fixture in [AutoTranslateCoordinatorTestBase].
 */
class AutoTranslateCoordinatorManualAttemptTest : AutoTranslateCoordinatorTestBase() {

    @Test
    fun `a stale manual wait does not overwrite a newer attempt on the same row`() = coordinatorTest {
        val firstWait = CompletableDeferred<Boolean>()
        val secondWait = CompletableDeferred<Boolean>()
        val waits = ConcurrentLinkedQueue(listOf(firstWait, secondWait))
        coEvery { modelStore.awaitReady(TranslateLang.ZH) } coAnswers { waits.poll()!!.await() }
        every { modelStore.isReady(any<TranslateLang>()) } returns false
        startApplied()
        answeringSubmit()
        val target = message("m0")
        coordinator.onWindowAssembled(listOf(target))

        // Attach the attempts one at a time: the launched waits run on a real dispatcher, so two
        // back-to-back calls could poll the gates in either order.
        coordinator.translateManually(target, TranslateTargetLanguage.ZH)
        awaitCondition("the first wait attached") { waits.size == 1 }
        coordinator.translateManually(target, TranslateTargetLanguage.ZH)
        awaitCondition("the second wait attached") { waits.isEmpty() }

        // The older wait gives up after the retry took over the row: its failure must not land.
        firstWait.complete(false)
        delay(SETTLE_MS)
        assertEquals(TranslateStatus.Translating, coordinator.overlay.value.getValue("m0").translateStatus)
        assertEquals(0, submissions.size)

        secondWait.complete(true)
        awaitOverlay("the newer attempt's translation") { it["m0"]?.translateStatus == TranslateStatus.ShowCN }
        assertEquals(1, submissions.size)
    }

    @Test
    fun `a manual same-language result shows the original text and is persisted`() = coordinatorTest {
        startApplied()
        val target = message("m0")
        coordinator.onWindowAssembled(listOf(target))
        coordinator.translateManually(target, TranslateTargetLanguage.EN)
        awaitCondition("the manual job to be submitted") { submissions.size == 1 }

        // What the engine answers in MANUAL mode for text already in the target language.
        completions.emit(
            TranslateQueue.Completion(
                owner = "$ROOM#manual",
                key = submissions.single().key,
                messageId = "m0",
                target = TranslateLang.EN,
                result = TranslateResult.Translated("hello m0", "en"),
            )
        )

        awaitOverlay("the original shown as the result") { it["m0"]?.translateStatus == TranslateStatus.ShowEN }
        assertEquals("hello m0", coordinator.overlay.value.getValue("m0").translatedContentEN)
        coVerify(timeout = BARRIER_MS) {
            dbMessageStore.updateMessageTranslateData(
                ROOM,
                "m0",
                match { it.translateStatus == TranslateStatus.ShowEN && it.translatedContentEN == "hello m0" },
            )
        }
    }
}
