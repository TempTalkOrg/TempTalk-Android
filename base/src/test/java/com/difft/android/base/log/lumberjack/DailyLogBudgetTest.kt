package com.difft.android.base.log.lumberjack

import android.util.Log
import com.difft.android.base.log.lumberjack.DailyLogBudget.Admission
import kotlin.test.Test
import kotlin.test.assertEquals

class DailyLogBudgetTest {

    @Test
    fun infoWithinBudgetIsWritten() {
        val budget = DailyLogBudget(limitBytes = 100)
        assertEquals(Admission.WRITE, budget.admit("2026-10-10", Log.INFO, 60))
        assertEquals(Admission.WRITE, budget.admit("2026-10-10", Log.INFO, 40))
    }

    @Test
    fun firstInfoOverBudgetWritesMarker_thenDrops() {
        val budget = DailyLogBudget(limitBytes = 100)
        budget.admit("2026-10-10", Log.INFO, 100)
        assertEquals(Admission.WRITE_EXHAUSTED_MARKER, budget.admit("2026-10-10", Log.INFO, 1))
        assertEquals(Admission.DROP, budget.admit("2026-10-10", Log.INFO, 1))
        assertEquals(Admission.DROP, budget.admit("2026-10-10", Log.INFO, 1))
    }

    @Test
    fun warnAndErrorAreNeverDroppedAndDoNotConsumeBudget() {
        val budget = DailyLogBudget(limitBytes = 100)
        assertEquals(Admission.WRITE, budget.admit("2026-10-10", Log.WARN, 500))
        assertEquals(Admission.WRITE, budget.admit("2026-10-10", Log.ERROR, 500))
        assertEquals(Admission.WRITE, budget.admit("2026-10-10", Log.ASSERT, 500))
        assertEquals(Admission.WRITE, budget.admit("2026-10-10", Log.INFO, 100))
        assertEquals(Admission.WRITE_EXHAUSTED_MARKER, budget.admit("2026-10-10", Log.INFO, 1))
        assertEquals(Admission.WRITE, budget.admit("2026-10-10", Log.WARN, 500))
    }

    @Test
    fun newDayResetsBudgetAndMarker() {
        val budget = DailyLogBudget(limitBytes = 10)
        assertEquals(Admission.WRITE, budget.admit("2026-10-10", Log.INFO, 5))
        assertEquals(Admission.WRITE_EXHAUSTED_MARKER, budget.admit("2026-10-10", Log.INFO, 50))
        assertEquals(Admission.DROP, budget.admit("2026-10-10", Log.INFO, 1))
        assertEquals(Admission.WRITE, budget.admit("2026-10-11", Log.INFO, 5))
        assertEquals(Admission.WRITE_EXHAUSTED_MARKER, budget.admit("2026-10-11", Log.INFO, 50))
    }
}
