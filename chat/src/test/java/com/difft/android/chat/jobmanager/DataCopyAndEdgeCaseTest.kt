package com.difft.android.chat.jobmanager

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DataCopyAndEdgeCaseTest {

    // region Builder copy constructor (buildUpon)

    @Test
    fun `buildUpon preserves existing data`() {
        val original = Data.Builder()
            .putString("str", "hello")
            .putInt("num", 42)
            .putBoolean("flag", true)
            .putLong("big", 999L)
            .build()

        val copy = original.buildUpon().build()

        assertEquals("hello", copy.getString("str"))
        assertEquals(42, copy.getInt("num"))
        assertTrue(copy.getBoolean("flag"))
        assertEquals(999L, copy.getLong("big"))
    }

    @Test
    fun `buildUpon allows adding new data`() {
        val original = Data.Builder()
            .putString("str", "hello")
            .build()

        val extended = original.buildUpon()
            .putInt("num", 100)
            .build()

        assertEquals("hello", extended.getString("str"))
        assertEquals(100, extended.getInt("num"))
    }

    @Test
    fun `buildUpon allows overriding existing data`() {
        val original = Data.Builder()
            .putString("str", "hello")
            .build()

        val modified = original.buildUpon()
            .putString("str", "world")
            .build()

        assertEquals("world", modified.getString("str"))
    }

    @Test
    fun `buildUpon preserves all array types`() {
        val original = Data.Builder()
            .putStringArray("sa", arrayOf("x", "y"))
            .putIntArray("ia", intArrayOf(1, 2))
            .putLongArray("la", longArrayOf(3L, 4L))
            .putFloatArray("fa", floatArrayOf(1.0f, 2.0f))
            .putDoubleArray("da", doubleArrayOf(3.0, 4.0))
            .putBooleanArray("ba", booleanArrayOf(true, false))
            .putByteArray("bya", byteArrayOf(5, 6))
            .build()

        val copy = original.buildUpon().build()

        assertContentEquals(arrayOf("x", "y"), copy.getStringArray("sa"))
        assertContentEquals(intArrayOf(1, 2), copy.getIntegerArray("ia"))
        assertContentEquals(longArrayOf(3L, 4L), copy.getLongArray("la"))
        assertContentEquals(floatArrayOf(1.0f, 2.0f), copy.getFloatArray("fa"))
        assertContentEquals(doubleArrayOf(3.0, 4.0), copy.getDoubleArray("da"))
        assertContentEquals(booleanArrayOf(true, false), copy.getBooleanArray("ba"))
        assertContentEquals(byteArrayOf(5, 6), copy.getByteArray("bya"))
    }

    // endregion

    // region List helper methods

    @Test
    fun `putStringListAsArray and getStringArrayAsList roundtrip`() {
        val list = listOf("alpha", "beta", "gamma")
        val data = Data.Builder()
            .putStringListAsArray("key", list)
            .build()

        assertEquals(list, data.getStringArrayAsList("key"))
    }

    @Test
    fun `putLongListAsArray and getLongArrayAsList roundtrip`() {
        val list = listOf(10L, 20L, 30L)
        val data = Data.Builder()
            .putLongListAsArray("key", list)
            .build()

        assertEquals(list, data.getLongArrayAsList("key"))
    }

    @Test
    fun `getStringArrayAsList throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getStringArrayAsList("missing")
        }
    }

    @Test
    fun `getLongArrayAsList throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getLongArrayAsList("missing")
        }
    }

    // endregion

    // region Multiple keys

    @Test
    fun `multiple keys of same type are independent`() {
        val data = Data.Builder()
            .putString("a", "alpha")
            .putString("b", "beta")
            .putString("c", "gamma")
            .build()

        assertEquals("alpha", data.getString("a"))
        assertEquals("beta", data.getString("b"))
        assertEquals("gamma", data.getString("c"))
    }

    @Test
    fun `same key name across different types are independent`() {
        val data = Data.Builder()
            .putString("key", "text")
            .putInt("key", 42)
            .putLong("key", 100L)
            .putBoolean("key", true)
            .build()

        assertEquals("text", data.getString("key"))
        assertEquals(42, data.getInt("key"))
        assertEquals(100L, data.getLong("key"))
        assertTrue(data.getBoolean("key"))
    }

    // endregion
}
