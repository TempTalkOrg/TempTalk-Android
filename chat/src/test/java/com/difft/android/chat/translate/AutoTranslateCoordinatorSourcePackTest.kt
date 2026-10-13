package com.difft.android.chat.translate

import com.difft.android.chat.translate.model.ModelState
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.TranslateTargetLanguage
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [AutoTranslateCoordinator] on a manual translation whose SOURCE language pack is missing — a
 * pack only the engine can discover, after identifying the text. The row keeps its placeholder
 * while the pack downloads and the job runs once more, instead of failing on the first answer.
 * Fixture in [AutoTranslateCoordinatorTestBase].
 */
class AutoTranslateCoordinatorSourcePackTest : AutoTranslateCoordinatorTestBase() {

    private val ja = ModelId("ja")
    private val notReadyJa = TranslateResult.ModelNotReady(setOf(ja))

    /** Answers handed out in submit order; once drained, every submit translates. */
    private val answers = ConcurrentLinkedQueue<TranslateResult>()

    @Before
    fun scriptTheQueue() {
        every { queue.submit(any(), any(), any(), any(), any(), any(), any()) } answers {
            val owner = arg<String>(0)
            val key = arg<String>(1)
            val messageId = arg<String>(2)
            val target = arg<TranslateLang>(5)
            submissions += Submission(owner, key, messageId, arg(4))
            val result = answers.poll() ?: TranslateResult.Translated("译:$messageId", "ja")
            // Broadcast like the real worker; the collector must not turn a manual not-ready into a failure.
            scope.launch { completions.emit(TranslateQueue.Completion(owner, key, messageId, target, result)) }
            CompletableDeferred(result) as Deferred<TranslateResult>
        }
    }

    private fun translateJapanese(id: String) {
        startApplied()
        val target = message(id)
        coordinator.onWindowAssembled(listOf(target))
        coordinator.translateManually(target, TranslateTargetLanguage.ZH)
    }

    @Test
    fun `a missing source pack keeps the placeholder and translates after the pack lands`() = coordinatorTest {
        val pack = CompletableDeferred<Boolean>()
        coEvery { modelStore.awaitReady(setOf(ja)) } coAnswers { pack.await() }
        answers += notReadyJa

        translateJapanese("m0")
        awaitCondition("the first submit") { submissions.size == 1 }
        delay(SETTLE_MS)
        assertEquals(TranslateStatus.Translating, coordinator.overlay.value.getValue("m0").translateStatus)

        pack.complete(true)

        awaitOverlay("the translation") { it["m0"]?.translateStatus == TranslateStatus.ShowCN }
        assertEquals(2, submissions.size)
        assertEquals(setOf("m0#ZH#manual"), submissions.map { it.key }.toSet())
    }

    @Test
    fun `a pack that lands but still answers not-ready fails after exactly one retry`() = coordinatorTest {
        answers += notReadyJa
        answers += notReadyJa

        translateJapanese("m0")

        awaitOverlay("the model failure state") { it["m0"]?.translateStatus == TranslateStatus.FailedModel }
        delay(SETTLE_MS)
        assertEquals(2, submissions.size)
    }

    @Test
    fun `a source pack wait that gives up fails, then resumes once that pack is ready`() = coordinatorTest {
        coEvery { modelStore.awaitReady(setOf(ja)) } returns false
        answers += notReadyJa

        translateJapanese("m0")
        awaitOverlay("the model failure state") { it["m0"]?.translateStatus == TranslateStatus.FailedModel }
        assertEquals(1, submissions.size)

        // The target pack alone is not enough: the entry also waits for the source pack.
        modelState.value = mapOf(ModelId("zh") to ModelState.Ready)
        delay(SETTLE_MS)
        assertEquals(1, submissions.size)

        modelState.value = mapOf(ModelId("zh") to ModelState.Ready, ja to ModelState.Ready)

        awaitOverlay("the resumed translation") { it["m0"]?.translateStatus == TranslateStatus.ShowCN }
        assertEquals(2, submissions.size)
    }
}
