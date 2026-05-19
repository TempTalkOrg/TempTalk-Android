package com.difft.android.chat.jobmanager

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataHasAndEmptyTest {

    // region has* methods

    @Test
    fun `hasString returns true when present`() {
        val data = Data.Builder().putString("key", "value").build()
        assertTrue(data.hasString("key"))
    }

    @Test
    fun `hasString returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasString("missing"))
    }

    @Test
    fun `hasString returns true for null value`() {
        val data = Data.Builder().putString("key", null).build()
        assertTrue(data.hasString("key"))
    }

    @Test
    fun `hasInt returns true when present`() {
        val data = Data.Builder().putInt("key", 1).build()
        assertTrue(data.hasInt("key"))
    }

    @Test
    fun `hasInt returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasInt("missing"))
    }

    @Test
    fun `hasLong returns true when present`() {
        val data = Data.Builder().putLong("key", 1L).build()
        assertTrue(data.hasLong("key"))
    }

    @Test
    fun `hasLong returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasLong("missing"))
    }

    @Test
    fun `hasFloat returns true when present`() {
        val data = Data.Builder().putFloat("key", 1.0f).build()
        assertTrue(data.hasFloat("key"))
    }

    @Test
    fun `hasFloat returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasFloat("missing"))
    }

    @Test
    fun `hasDouble returns true when present`() {
        val data = Data.Builder().putDouble("key", 1.0).build()
        assertTrue(data.hasDouble("key"))
    }

    @Test
    fun `hasDouble returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasDouble("missing"))
    }

    @Test
    fun `hasBoolean returns true when present`() {
        val data = Data.Builder().putBoolean("key", true).build()
        assertTrue(data.hasBoolean("key"))
    }

    @Test
    fun `hasBoolean returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasBoolean("missing"))
    }

    @Test
    fun `hasStringArray returns true when present`() {
        val data = Data.Builder().putStringArray("key", arrayOf("a")).build()
        assertTrue(data.hasStringArray("key"))
    }

    @Test
    fun `hasStringArray returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasStringArray("missing"))
    }

    @Test
    fun `hasIntegerArray returns true when present`() {
        val data = Data.Builder().putIntArray("key", intArrayOf(1)).build()
        assertTrue(data.hasIntegerArray("key"))
    }

    @Test
    fun `hasIntegerArray returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasIntegerArray("missing"))
    }

    @Test
    fun `hasLongArray returns true when present`() {
        val data = Data.Builder().putLongArray("key", longArrayOf(1L)).build()
        assertTrue(data.hasLongArray("key"))
    }

    @Test
    fun `hasLongArray returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasLongArray("missing"))
    }

    @Test
    fun `hasFloatArray returns true when present`() {
        val data = Data.Builder().putFloatArray("key", floatArrayOf(1.0f)).build()
        assertTrue(data.hasFloatArray("key"))
    }

    @Test
    fun `hasFloatArray returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasFloatArray("missing"))
    }

    @Test
    fun `hasDoubleArray returns true when present`() {
        val data = Data.Builder().putDoubleArray("key", doubleArrayOf(1.0)).build()
        assertTrue(data.hasDoubleArray("key"))
    }

    @Test
    fun `hasDoubleArray returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasDoubleArray("missing"))
    }

    @Test
    fun `hasBooleanArray returns true when present`() {
        val data = Data.Builder().putBooleanArray("key", booleanArrayOf(true)).build()
        assertTrue(data.hasBooleanArray("key"))
    }

    @Test
    fun `hasBooleanArray returns false when absent`() {
        val data = Data.Builder().build()
        assertFalse(data.hasBooleanArray("missing"))
    }

    // endregion

    // region Data.EMPTY

    @Test
    fun `EMPTY has no strings`() {
        assertFalse(Data.EMPTY.hasString("anything"))
    }

    @Test
    fun `EMPTY has no ints`() {
        assertFalse(Data.EMPTY.hasInt("anything"))
    }

    @Test
    fun `EMPTY has no longs`() {
        assertFalse(Data.EMPTY.hasLong("anything"))
    }

    @Test
    fun `EMPTY has no floats`() {
        assertFalse(Data.EMPTY.hasFloat("anything"))
    }

    @Test
    fun `EMPTY has no doubles`() {
        assertFalse(Data.EMPTY.hasDouble("anything"))
    }

    @Test
    fun `EMPTY has no booleans`() {
        assertFalse(Data.EMPTY.hasBoolean("anything"))
    }

    @Test
    fun `EMPTY has no string arrays`() {
        assertFalse(Data.EMPTY.hasStringArray("anything"))
    }

    @Test
    fun `EMPTY has no integer arrays`() {
        assertFalse(Data.EMPTY.hasIntegerArray("anything"))
    }

    @Test
    fun `EMPTY has no long arrays`() {
        assertFalse(Data.EMPTY.hasLongArray("anything"))
    }

    @Test
    fun `EMPTY has no float arrays`() {
        assertFalse(Data.EMPTY.hasFloatArray("anything"))
    }

    @Test
    fun `EMPTY has no double arrays`() {
        assertFalse(Data.EMPTY.hasDoubleArray("anything"))
    }

    @Test
    fun `EMPTY has no boolean arrays`() {
        assertFalse(Data.EMPTY.hasBooleanArray("anything"))
    }

    // endregion
}
