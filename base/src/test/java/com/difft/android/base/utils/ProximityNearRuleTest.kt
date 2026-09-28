package com.difft.android.base.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProximityNearRuleTest {

    @Test
    fun `physical sensor reporting zero and its maximum`() {
        assertTrue(ProximityNearRule.isNear(0f, 5f))
        assertFalse(ProximityNearRule.isNear(5f, 5f))
    }

    @Test
    fun `far reading below the advertised maximum is not near`() {
        // The defect class behind #1252: FAR = 5 while maximumRange = 10 was NEAR under `value < maximumRange`.
        assertFalse(ProximityNearRule.isNear(5f, 10f))
        assertFalse(ProximityNearRule.isNear(7f, 10f))
        assertTrue(ProximityNearRule.isNear(3f, 10f))
    }

    @Test
    fun `binary sensor with a small maximum`() {
        assertTrue(ProximityNearRule.isNear(0f, 1f))
        assertFalse(ProximityNearRule.isNear(1f, 1f))
    }

    @Test
    fun `negative readings are never near`() {
        assertFalse(ProximityNearRule.isNear(-1f, 5f))
    }

    @Test
    fun `non-positive maximum falls back to the system threshold`() {
        assertTrue(ProximityNearRule.isNear(2f, 0f))
        assertFalse(ProximityNearRule.isNear(5f, 0f))
    }
}
