package com.difft.android.chat.translate

import io.mockk.coVerify
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test






class AutoTranslateCoordinatorScenarioATest : AutoTranslateCoordinatorTestBase() {


    @Test
    fun `T26 with the setting off awaitNewIncoming submits nothing`() = coordinatorTest {
        startApplied(setting = 0)

        coordinator.awaitNewIncoming(behaviorOf(3))

        assertEquals(emptyList<Submission>(), submissions.toList())
        coVerify(exactly = 0) { hydrator.hydrate(any()) }
    }

    @Test
    fun `T27 with the model missing awaitNewIncoming submits nothing`() = coordinatorTest {
        every { modelStore.isReady(any<TranslateLang>()) } returns false
        startApplied()

        coordinator.awaitNewIncoming(behaviorOf(3))

        assertEquals(emptyList<Submission>(), submissions.toList())
        coVerify(exactly = 0) { hydrator.hydrate(any()) }
    }


    @Test
    fun `T28 the first scenario-A pass is budgeted to the newest ten messages`() = coordinatorTest {
        startApplied()
        answeringSubmit()

        coordinator.awaitNewIncoming(behaviorOf(15))

        assertEquals(FIRST_SCREEN_MAX, submissions.size)
        assertEquals(
            (5 until 15).mapTo(mutableSetOf()) { "m$it" },
            submissions.mapTo(mutableSetOf()) { it.messageId },
        )
        assertTrue(submissions.all { it.owner == ROOM })
    }

    @Test
    fun `T28 a message is claimed once, so a second pass over the same window submits nothing`() =
        coordinatorTest {
            startApplied()
            answeringSubmit()
            val behavior = behaviorOf(3)

            coordinator.awaitNewIncoming(behavior)
            val firstPass = submissions.size
            coordinator.awaitNewIncoming(behavior)

            assertEquals(3, firstPass)
            assertEquals(firstPass, submissions.size)
        }


    @Test
    fun `T29 a budget hit returns well inside the window and lands the translation`() = coordinatorTest {
        startApplied()
        answeringSubmit()

        val startedAt = System.currentTimeMillis()
        coordinator.awaitNewIncoming(behaviorOf(1))
        val cost = System.currentTimeMillis() - startedAt

        assertTrue("expected a budget hit, took ${cost}ms", cost < NEW_INCOMING_BUDGET_MS)
        
        
        
        assertEquals(setOf("m0"), coordinator.overlay.value.keys)
        assertEquals("译:m0", coordinator.overlay.value.getValue("m0").translatedContentCN)
    }

    @Test
    fun `T30 a budget miss costs the whole window and returns anyway`() = coordinatorTest {
        startApplied()
        
        val startedAt = System.currentTimeMillis()
        coordinator.awaitNewIncoming(behaviorOf(1))
        val cost = System.currentTimeMillis() - startedAt

        assertEquals(1, submissions.size)
        assertTrue("expected the full budget, took ${cost}ms", cost >= NEW_INCOMING_BUDGET_MS)
        assertTrue("nothing may be rendered yet", coordinator.overlay.value.isEmpty())
    }

    @Test
    fun `T30b a result arriving after the budget still reaches the overlay and the batch write`() =
        coordinatorTest {
            startApplied()
            coordinator.awaitNewIncoming(behaviorOf(1))
            assertEquals(1, submissions.size)

            
            completeAuto("m0")

            awaitOverlay("the late translation") { it.keys == setOf("m0") }
            coVerify(timeout = BARRIER_MS) {
                dbMessageStore.updateMessageTranslateDataBatch(ROOM, match { "m0" in it.keys })
            }
        }
}
