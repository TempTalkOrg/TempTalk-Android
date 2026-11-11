package com.difft.android.chat.focus

import org.junit.Test
import kotlin.test.assertEquals


class DurationSheetOutcomeTest {

    @Test
    fun `dismissal without a choice is reported as cancel once`() {
        var cancels = 0
        val outcome = DurationSheetOutcome { cancels++ }

        outcome.onDismissed()
        outcome.onDismissed()

        assertEquals(1, cancels)
    }

    @Test
    fun `dismissal after a choice is not a cancel`() {
        var cancels = 0
        val outcome = DurationSheetOutcome { cancels++ }

        outcome.markChosen()
        outcome.onDismissed()

        assertEquals(0, cancels)
    }
}
