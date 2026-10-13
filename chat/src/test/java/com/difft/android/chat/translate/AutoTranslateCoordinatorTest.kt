package com.difft.android.chat.translate

import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.chat.setting.ConversationSettingUpdate
import com.difft.android.chat.translate.model.ModelState
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.TranslateTargetLanguage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [AutoTranslateCoordinator] delivery: batching, persistence filtering, target changes, the manual
 * path and teardown. Scenario A lives in [AutoTranslateCoordinatorScenarioATest]; the shared
 * fixture is in [AutoTranslateCoordinatorTestBase].
 *
 * What each case asserts:
 *  - T25  a batch of three results is applied as ONE overlay update and ONE batch write
 *  - the in-page batch write runs on the global scope, so a page teardown cannot eat it
 *  - T31  a setting change cancels only the automatic owner and clears the attempted claims
 *  - T31b a result for the previous target is discarded
 *  - T32  translateOff hides the block, keeps the text, persists, and blocks re-admission
 *  - T33  only persistable statuses are written, and the batch never calls trackRoom
 *  - close() drains the batch that was still waiting for the timer, and a completion that arrives
 *    afterwards changes nothing
 *  - a user action's write and the close-time drain both survive the scope cancel that precedes
 *    close(), and both drop themselves when the queue's epoch moved (logout)
 *  - a build with no translate backend reads a stored target as off and submits nothing
 *  - a manual translation keeps its placeholder while the model downloads, then translates; a
 *    failed or throwing download (or a model-not-ready result) ends in FailedModel; leaving the
 *    page while waiting rolls the placeholder back; turning it off while waiting is final
 */
/** Shorter than the incoming budget, so a wait that was wrongly opened is caught while still pending. */
private const val STALE_SETTLE_MS = 150L

class AutoTranslateCoordinatorTest : AutoTranslateCoordinatorTestBase() {

    // --- T25 / T33 ----------------------------------------------------------

    @Test
    fun `T25 three results are applied as one overlay update and one batch write`() = coordinatorTest {
        startApplied()
        val emissionsBefore = overlayEmissions.size

        completeAuto("m1")
        completeAuto("m2")
        completeAuto("m3")

        awaitOverlay("all three translations") { it.keys == setOf("m1", "m2", "m3") }

        // ONE update, not three: the 800 ms batch is what keeps a screenful of arrivals from
        // re-assembling the list per message.
        assertEquals(1, overlayEmissions.size - emissionsBefore)
        assertEquals(TranslateStatus.ShowCN, coordinator.overlay.value.getValue("m1").translateStatus)
        assertEquals("译:m1", coordinator.overlay.value.getValue("m1").translatedContentCN)
        coVerify(timeout = BARRIER_MS, exactly = 1) {
            dbMessageStore.updateMessageTranslateDataBatch(ROOM, match { it.keys == setOf("m1", "m2", "m3") })
        }
    }

    @Test
    fun `T33 the batch persists only persistable statuses and never calls trackRoom`() = coordinatorTest {
        startApplied()

        completeAuto("ok")
        // AUTO renders successes only, so every non-success outcome produces no overlay entry at
        // all — which is what keeps a transient status out of the write in the first place.
        completeAuto("same", result = TranslateResult.SameLanguage)
        completeAuto("failed", result = TranslateResult.Failed(IllegalStateException()))
        completeAuto("long", result = TranslateResult.Unsupported(TranslateResult.Reason.TOO_LONG))
        completeAuto("notReady", result = TranslateResult.ModelNotReady(setOf(ModelId("zh"))))

        awaitOverlay("only the successful id") { it.keys == setOf("ok") }
        coVerify(timeout = BARRIER_MS) {
            dbMessageStore.updateMessageTranslateDataBatch(
                ROOM,
                match { batch -> batch.keys == setOf("ok") && batch.values.all { it.translateStatus.isPersistable } },
            )
        }
        // The batch deliberately does not re-run the conversation page's most expensive query.
        verify(exactly = 0) { RoomChangeTracker.trackRoom(any(), any()) }
    }

    // --- T31 / T31b ---------------------------------------------------------

    @Test
    fun `T31 a setting change cancels the automatic owner only and clears the claims`() = coordinatorTest {
        startApplied()
        answeringSubmit()
        coordinator.awaitNewIncoming(behaviorOf(2))
        // Delivery-based budget: the results are rendered by the time the wait returns.
        assertEquals(setOf("m0", "m1"), coordinator.overlay.value.keys)
        val beforeOverlay = coordinator.overlay.value
        val beforeSubmits = submissions.size

        settingUpdates.emit(ConversationSettingUpdate(conversationId = ROOM, translateSetting = 2))

        verify(timeout = BARRIER_MS, atLeast = 2) { queue.cancelOwner(ROOM) }
        // A manual translation the user is waiting for must survive a setting change.
        verify(exactly = 0) { queue.cancelOwner("$ROOM#manual") }
        // Existing translations are kept; only future admissions change.
        assertEquals(beforeOverlay, coordinator.overlay.value)

        // attempted was cleared, so the same window is admitted again — for the NEW target.
        coordinator.awaitNewIncoming(behaviorOf(2))
        val added = submissions.drop(beforeSubmits)
        assertEquals(2, added.size)
        assertTrue(added.all { it.key.endsWith("#${TranslateLang.EN.name}") })
    }

    @Test
    fun `T31b a result for the previous target is discarded`() = coordinatorTest {
        startApplied()

        completeAuto("m0", target = TranslateLang.EN)
        delay(SETTLE_MS)

        assertTrue(coordinator.overlay.value.isEmpty())
        coVerify(exactly = 0) { dbMessageStore.updateMessageTranslateDataBatch(any(), any()) }
    }

    @Test
    fun `T31c a leftover completion for the old target does not open the new target's wait`() =
        coordinatorTest {
            startApplied(setting = 2)
            neverAnsweringSubmit()
            var returned = false
            val wait = launch {
                coordinator.awaitNewIncoming(behaviorOf(1))
                returned = true
            }
            awaitCondition("the EN attempt is submitted") {
                submissions.any { it.key == attemptKey("m0", TranslateLang.EN) }
            }

            // Work left over from the previous target finishes now. The gate is keyed by attempt, so
            // this must neither open the EN wait nor land anything in the overlay.
            completeAuto("m0", target = TranslateLang.ZH)
            delay(STALE_SETTLE_MS)

            assertFalse("the EN wait must still be pending", returned)
            wait.join()
            assertTrue(coordinator.overlay.value.isEmpty())
        }

    // --- T32 ----------------------------------------------------------------

    @Test
    fun `T32 translateOff hides the block, keeps the text, persists and blocks re-admission`() =
        coordinatorTest {
            startApplied()
            answeringSubmit()
            val shown = message("m0", TranslateData(TranslateStatus.ShowCN, "你好", null))
            coordinator.onWindowAssembled(listOf(shown))

            coordinator.translateOff(shown)

            val off = coordinator.overlay.value.getValue("m0")
            assertEquals(TranslateStatus.Invisible, off.translateStatus)
            assertEquals("the translated text is kept, only hidden", "你好", off.translatedContentCN)
            coVerify(timeout = BARRIER_MS) {
                dbMessageStore.updateMessageTranslateData(
                    ROOM,
                    "m0",
                    match { it.translateStatus == TranslateStatus.Invisible },
                )
            }

            coordinator.onVisibleRange(listOf("m0"), VisibleReason.SCROLL_IDLE)
            delay(SETTLE_MS)
            assertEquals(emptyList<Submission>(), submissions.toList())
        }

    // --- manual path --------------------------------------------------------

    @Test
    fun `a manual translation shows a placeholder immediately and bypasses the batch`() = coordinatorTest {
        startApplied()
        val target = message("m0")
        coordinator.onWindowAssembled(listOf(target))

        coordinator.translateManually(target, TranslateTargetLanguage.ZH)

        assertEquals(TranslateStatus.Translating, coordinator.overlay.value.getValue("m0").translateStatus)
        awaitCondition("the manual job to be submitted once the model is ready") { submissions.size == 1 }
        val manual = submissions.single()
        assertEquals("$ROOM#manual", manual.owner)
        // The manual key is distinct from the automatic one, so a manual request can never join an
        // in-flight AUTO job and inherit its owner.
        assertEquals("m0#ZH#manual", manual.key)

        completeManual("m0", manual.key, TranslateResult.Translated("你好", "en"))

        awaitOverlay("the manual result, with no 800 ms batch wait") {
            it["m0"]?.translateStatus == TranslateStatus.ShowCN
        }
        coVerify(timeout = BARRIER_MS) { dbMessageStore.updateMessageTranslateData(ROOM, "m0", any()) }
        coVerify(exactly = 0) { dbMessageStore.updateMessageTranslateDataBatch(any(), any()) }
    }

    @Test
    fun `a manual failure is reported, unlike an automatic one`() = coordinatorTest {
        startApplied()
        val target = message("m0")
        coordinator.onWindowAssembled(listOf(target))
        coordinator.translateManually(target, TranslateTargetLanguage.ZH)

        completeManual("m0", "m0#ZH#manual", TranslateResult.Unsupported(TranslateResult.Reason.TOO_LONG))

        awaitOverlay("the too-long error state") {
            it["m0"]?.translateStatus == TranslateStatus.FailedTooLong
        }
    }

    @Test
    fun `a manual translation waits for the model, then translates`() = coordinatorTest {
        val download = CompletableDeferred<Boolean>()
        coEvery { modelStore.awaitReady(TranslateLang.ZH) } coAnswers { download.await() }
        startApplied()
        answeringSubmit()
        val target = message("m0")
        coordinator.onWindowAssembled(listOf(target))

        coordinator.translateManually(target, TranslateTargetLanguage.ZH)
        delay(STALE_SETTLE_MS)

        assertEquals(TranslateStatus.Translating, coordinator.overlay.value.getValue("m0").translateStatus)
        assertEquals("nothing may reach the queue while the model is missing", 0, submissions.size)

        download.complete(true)

        awaitOverlay("the translation once the model is ready") {
            it["m0"]?.translateStatus == TranslateStatus.ShowCN
        }
        assertEquals("m0#ZH#manual", submissions.single().key)
    }

    @Test
    fun `a manual translation whose model download fails reports a model failure`() = coordinatorTest {
        val download = CompletableDeferred<Boolean>()
        coEvery { modelStore.awaitReady(TranslateLang.ZH) } coAnswers { download.await() }
        startApplied()
        val target = message("m0")
        coordinator.onWindowAssembled(listOf(target))

        coordinator.translateManually(target, TranslateTargetLanguage.ZH)
        delay(STALE_SETTLE_MS)
        assertEquals(TranslateStatus.Translating, coordinator.overlay.value.getValue("m0").translateStatus)

        download.complete(false)

        awaitOverlay("the model failure state") {
            it["m0"]?.translateStatus == TranslateStatus.FailedModel
        }
        assertEquals(0, submissions.size)
    }

    @Test
    fun `turning translation off while waiting for the model is final, whatever the download does`() =
        coordinatorTest {
            val downloads = ConcurrentLinkedQueue(listOf(CompletableDeferred<Boolean>(), CompletableDeferred()))
            val (succeeds, fails) = downloads.toList()
            coEvery { modelStore.awaitReady(TranslateLang.ZH) } coAnswers { downloads.remove().await() }
            startApplied()
            answeringSubmit()
            val messages = listOf(message("m0"), message("m1"))
            coordinator.onWindowAssembled(messages)
            messages.forEach { coordinator.translateManually(it, TranslateTargetLanguage.ZH) }
            awaitCondition("both manual waits to start") { downloads.isEmpty() }

            messages.forEach { coordinator.translateOff(it) }
            succeeds.complete(true)
            fails.complete(false)
            delay(STALE_SETTLE_MS)

            messages.forEach {
                assertEquals(TranslateStatus.Invisible, coordinator.overlay.value.getValue(it.id).translateStatus)
            }
            assertEquals("nothing is submitted after the user turned it off", 0, submissions.size)
        }

    @Test
    fun `a manual wait that throws reports a model failure instead of crashing`() = coordinatorTest {
        coEvery { modelStore.awaitReady(TranslateLang.ZH) } throws IllegalStateException("datastore read")
        startApplied()
        val target = message("m0")
        coordinator.onWindowAssembled(listOf(target))

        coordinator.translateManually(target, TranslateTargetLanguage.ZH)

        awaitOverlay("the model failure state") { it["m0"]?.translateStatus == TranslateStatus.FailedModel }
        assertEquals(0, submissions.size)
    }

    @Test
    fun `leaving the page while waiting for the model rolls the placeholder back`() = coordinatorTest {
        coEvery { modelStore.awaitReady(TranslateLang.ZH) } coAnswers { awaitCancellation() }
        startApplied()
        val target = message("m0")
        coordinator.onWindowAssembled(listOf(target))
        coordinator.translateManually(target, TranslateTargetLanguage.ZH)
        assertEquals(TranslateStatus.Translating, coordinator.overlay.value.getValue("m0").translateStatus)

        scope.cancel()

        awaitOverlay("the placeholder rolled back") {
            it["m0"]?.translateStatus == TranslateStatus.Invisible
        }
    }

    // --- model ready --------------------------------------------------------

    @Test
    fun `a model finishing its download sweeps the last reported visible range`() = coordinatorTest {
        every { modelStore.isReady(any<TranslateLang>()) } returns false
        startApplied()
        answeringSubmit()
        coordinator.onWindowAssembled(listOf(message("m0")))
        coordinator.onVisibleRange(listOf("m0"), VisibleReason.FIRST_SCREEN)
        delay(SETTLE_MS)
        assertEquals("nothing may be submitted while the model is missing", 0, submissions.size)

        every { modelStore.isReady(any<TranslateLang>()) } returns true
        modelState.value = mapOf(ModelId("zh") to ModelState.Ready)

        awaitCondition("the viewport to be swept once the model is ready") { submissions.size == 1 }
        assertEquals("m0", submissions.single().messageId)
    }

    // --- close --------------------------------------------------------------

    @Test
    fun `close cancels both owners`() = coordinatorTest {
        startApplied()

        coordinator.close()

        verify { queue.cancelOwner(ROOM) }
        verify { queue.cancelOwner("$ROOM#manual") }
    }

    @Test
    fun `close writes the batch that was still waiting for the timer`() = coordinatorTest {
        startApplied()

        completeAuto("m1")
        // One collector, in order: once the manual result has been persisted, the automatic one is
        // provably already in the pending batch — and its 800 ms timer has not fired yet.
        completeManual("probe", "probe#ZH#manual", TranslateResult.Translated("你好", "en"))
        coVerify(timeout = BARRIER_MS) { dbMessageStore.updateMessageTranslateData(ROOM, "probe", any()) }

        // The real teardown order: AndroidX cancels viewModelScope, THEN calls onCleared(), which is
        // where close() runs. A drain that used the page's scope would already be too late here.
        scope.cancel()
        coordinator.close()

        coVerify(timeout = BARRIER_MS) {
            dbMessageStore.updateMessageTranslateDataBatch(ROOM, match { it.keys == setOf("m1") })
        }
    }

    @Test
    fun `flush persists even when the page scope is cancelled right after`() = coordinatorTest {
        startApplied()
        answeringSubmit()

        // The budget returns with the batch already flushed, so the write it launched is the only
        // thing still outstanding. It runs on the global scope precisely so the page going away in
        // the very next instant cannot drop the row and force the same translation again.
        coordinator.awaitNewIncoming(behaviorOf(1))
        scope.cancel()

        coVerify(timeout = BARRIER_MS) {
            dbMessageStore.updateMessageTranslateDataBatch(ROOM, match { it.keys == setOf("m0") })
        }
    }

    @Test
    fun `translateOff persists across the scope cancel that precedes close`() = coordinatorTest {
        startApplied()
        val early = message("m0", TranslateData(TranslateStatus.ShowCN, "你好", null))
        val late = message("m1", TranslateData(TranslateStatus.ShowCN, "世界", null))
        coordinator.onWindowAssembled(listOf(early, late))

        // The gesture as the user makes it: turn a translation off, then leave the conversation.
        coordinator.translateOff(early)
        // AndroidX cancels viewModelScope BEFORE onCleared() runs close(). A write launched on the
        // page's own scope is not merely racy in that window — once the scope is dead it never
        // starts at all, which is what the second action pins deterministically.
        scope.cancel()
        coordinator.translateOff(late)
        coordinator.close()

        // A user action's write runs on the global scope precisely so leaving the page cannot eat it.
        coVerify(timeout = BARRIER_MS) {
            dbMessageStore.updateMessageTranslateData(
                ROOM,
                "m0",
                match { it.translateStatus == TranslateStatus.Invisible },
            )
            dbMessageStore.updateMessageTranslateData(
                ROOM,
                "m1",
                match { it.translateStatus == TranslateStatus.Invisible },
            )
        }
    }

    @Test
    fun `a close-time drain after logout writes nothing`() = coordinatorTest {
        startApplied()

        completeAuto("m1")
        completeManual("probe", "probe#ZH#manual", TranslateResult.Translated("你好", "en"))
        coVerify(timeout = BARRIER_MS) { dbMessageStore.updateMessageTranslateData(ROOM, "probe", any()) }

        // What cancelAll() does on logout: the epoch moves away from the one captured at construction.
        every { queue.generation } returns 1L
        scope.cancel()
        coordinator.close()
        delay(SETTLE_MS)

        // The account is gone and the database with it; the drained batch must not be written.
        coVerify(exactly = 0) { dbMessageStore.updateMessageTranslateDataBatch(any(), any()) }
    }

    // --- no translate backend -----------------------------------------------

    /**
     * The F-Droid channel binds a backend that cannot translate. The stored target is left in the
     * database untouched — it becomes live again on a build that can translate — but nothing on the
     * message path may act on it.
     */
    @Test
    fun `a build with no translate backend reads a stored target as off`() = coordinatorTest {
        backend.isSupported = false
        every { dbRoomStore.getTranslateSetting(ROOM) } returns 1

        coordinator.start()
        verify(timeout = BARRIER_MS) { queue.cancelOwner(ROOM) }

        coordinator.awaitNewIncoming(behaviorOf(3))
        coordinator.onVisibleRange(listOf("m1", "m2"), VisibleReason.FIRST_SCREEN)
        delay(SETTLE_MS)

        assertTrue("nothing was submitted", submissions.isEmpty())
        verify(exactly = 0) { modelStore.ensureDownloaded(any<TranslateLang>(), any()) }
        assertTrue(coordinator.overlay.value.isEmpty())
    }

    @Test
    fun `a completion arriving after close is ignored`() = coordinatorTest {
        startApplied()

        coordinator.close()
        completeAuto("m2")
        // Longer than the batch timer: a scheduled flush would have run by now.
        delay(SETTLE_MS)

        assertTrue(coordinator.overlay.value.isEmpty())
        coVerify(exactly = 0) { dbMessageStore.updateMessageTranslateDataBatch(any(), any()) }
    }
}
