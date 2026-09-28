package com.difft.android.chat.jobmanager

import android.app.job.JobInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins [persistentJobId], the single derivation both sides of the persisted wake-up depend on:
 * the scheduler registers a job under it, and `JobController.pendingWakeUpIds` re-derives it from a
 * spec's stored constraints to decide which wake-up owns that spec. The two must never drift.
 */
class PersistentJobIdTest {

    @Test
    fun `a constraint-less group derives the empty-string id`() {
        assertEquals("".hashCode(), persistentJobId(emptyList()))
        assertEquals(PLAIN_JOB_ID, persistentJobId(emptyList()))
    }

    @Test
    fun `a single network constraint derives its key part`() {
        assertEquals("NETWORK".hashCode(), persistentJobId(listOf(TestNetworkConstraint())))
    }

    @Test
    fun `constraints that contribute no key part do not change the id`() {
        assertEquals(
            persistentJobId(emptyList()),
            persistentJobId(listOf(KeylessTestConstraint()))
        )
    }

    @Test
    fun `the id is independent of the order the constraints are given in`() {
        val forward = persistentJobId(listOf(TestNetworkConstraint(), KeyedTestConstraint()))
        val reversed = persistentJobId(listOf(KeyedTestConstraint(), TestNetworkConstraint()))

        assertEquals(forward, reversed)
        assertNotEquals(persistentJobId(listOf(TestNetworkConstraint())), forward)
    }
}

/** A second key part, so the sort in the derivation has something to reorder. */
private class KeyedTestConstraint : Constraint {
    override fun isMet(): Boolean = true

    override fun getFactoryKey(): String = "ChargingConstraint"

    override fun applyToJobInfo(jobInfoBuilder: JobInfo.Builder) {
        jobInfoBuilder.setRequiresCharging(true)
    }

    override fun getJobSchedulerKeyPart(): String = "CHARGING"
}

/** Applies nothing to the JobInfo, so it must not split a group off on its own. */
private class KeylessTestConstraint : Constraint {
    override fun isMet(): Boolean = true

    override fun getFactoryKey(): String = "KeylessConstraint"

    override fun applyToJobInfo(jobInfoBuilder: JobInfo.Builder) = Unit
}
