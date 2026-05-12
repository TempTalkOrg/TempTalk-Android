package com.difft.android.chat.jobmanager

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataBuilderAndGetterTest {

    // region Builder: put all types and verify getters

    @Test
    fun `builder puts and gets string`() {
        val data = Data.Builder()
            .putString("key", "value")
            .build()

        assertEquals("value", data.getString("key"))
    }

    @Test
    fun `builder puts and gets null string`() {
        val data = Data.Builder()
            .putString("key", null)
            .build()

        assertTrue(data.hasString("key"))
        assertNull(data.getString("key"))
    }

    @Test
    fun `builder puts and gets int`() {
        val data = Data.Builder()
            .putInt("key", 42)
            .build()

        assertEquals(42, data.getInt("key"))
    }

    @Test
    fun `builder puts and gets long`() {
        val data = Data.Builder()
            .putLong("key", 123456789L)
            .build()

        assertEquals(123456789L, data.getLong("key"))
    }

    @Test
    fun `builder puts and gets float`() {
        val data = Data.Builder()
            .putFloat("key", 3.14f)
            .build()

        assertEquals(3.14f, data.getFloat("key"))
    }

    @Test
    fun `builder puts and gets double`() {
        val data = Data.Builder()
            .putDouble("key", 2.718281828)
            .build()

        assertEquals(2.718281828, data.getDouble("key"))
    }

    @Test
    fun `builder puts and gets boolean`() {
        val data = Data.Builder()
            .putBoolean("key", true)
            .build()

        assertTrue(data.getBoolean("key"))
    }

    @Test
    fun `builder puts and gets byte array`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val data = Data.Builder()
            .putByteArray("key", bytes)
            .build()

        assertContentEquals(bytes, data.getByteArray("key"))
    }

    @Test
    fun `builder puts and gets string array`() {
        val arr = arrayOf("a", "b", "c")
        val data = Data.Builder()
            .putStringArray("key", arr)
            .build()

        assertContentEquals(arr, data.getStringArray("key"))
    }

    @Test
    fun `builder puts and gets int array`() {
        val arr = intArrayOf(1, 2, 3)
        val data = Data.Builder()
            .putIntArray("key", arr)
            .build()

        assertContentEquals(arr, data.getIntegerArray("key"))
    }

    @Test
    fun `builder puts and gets long array`() {
        val arr = longArrayOf(10L, 20L, 30L)
        val data = Data.Builder()
            .putLongArray("key", arr)
            .build()

        assertContentEquals(arr, data.getLongArray("key"))
    }

    @Test
    fun `builder puts and gets float array`() {
        val arr = floatArrayOf(1.1f, 2.2f, 3.3f)
        val data = Data.Builder()
            .putFloatArray("key", arr)
            .build()

        assertContentEquals(arr, data.getFloatArray("key"))
    }

    @Test
    fun `builder puts and gets double array`() {
        val arr = doubleArrayOf(1.1, 2.2, 3.3)
        val data = Data.Builder()
            .putDoubleArray("key", arr)
            .build()

        assertContentEquals(arr, data.getDoubleArray("key"))
    }

    @Test
    fun `builder puts and gets boolean array`() {
        val arr = booleanArrayOf(true, false, true)
        val data = Data.Builder()
            .putBooleanArray("key", arr)
            .build()

        assertContentEquals(arr, data.getBooleanArray("key"))
    }

    // endregion

    // region getString / getStringOrDefault

    @Test
    fun `getString throws for absent key`() {
        val data = Data.Builder().build()

        val exception = assertFailsWith<IllegalStateException> {
            data.getString("missing")
        }
        assertTrue(exception.message!!.contains("missing"))
    }

    @Test
    fun `getStringOrDefault returns value when present`() {
        val data = Data.Builder()
            .putString("key", "value")
            .build()

        assertEquals("value", data.getStringOrDefault("key", "default"))
    }

    @Test
    fun `getStringOrDefault returns default when absent`() {
        val data = Data.Builder().build()

        assertEquals("default", data.getStringOrDefault("missing", "default"))
    }

    @Test
    fun `getStringOrDefault returns null value when key present with null`() {
        val data = Data.Builder()
            .putString("key", null)
            .build()

        assertNull(data.getStringOrDefault("key", "default"))
    }

    // endregion

    // region getInt / getIntOrDefault

    @Test
    fun `getInt throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getInt("missing")
        }
    }

    @Test
    fun `getIntOrDefault returns value when present`() {
        val data = Data.Builder()
            .putInt("key", 42)
            .build()

        assertEquals(42, data.getIntOrDefault("key", 0))
    }

    @Test
    fun `getIntOrDefault returns default when absent`() {
        val data = Data.Builder().build()

        assertEquals(99, data.getIntOrDefault("missing", 99))
    }

    // endregion

    // region getLong / getLongOrDefault

    @Test
    fun `getLong throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getLong("missing")
        }
    }

    @Test
    fun `getLongOrDefault returns value when present`() {
        val data = Data.Builder()
            .putLong("key", 100L)
            .build()

        assertEquals(100L, data.getLongOrDefault("key", 0L))
    }

    @Test
    fun `getLongOrDefault returns default when absent`() {
        val data = Data.Builder().build()

        assertEquals(999L, data.getLongOrDefault("missing", 999L))
    }

    // endregion

    // region getFloat / getFloatOrDefault

    @Test
    fun `getFloat throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getFloat("missing")
        }
    }

    @Test
    fun `getFloatOrDefault returns value when present`() {
        val data = Data.Builder()
            .putFloat("key", 1.5f)
            .build()

        assertEquals(1.5f, data.getFloatOrDefault("key", 0f))
    }

    @Test
    fun `getFloatOrDefault returns default when absent`() {
        val data = Data.Builder().build()

        assertEquals(9.9f, data.getFloatOrDefault("missing", 9.9f))
    }

    // endregion

    // region getDouble / getDoubleOrDefault

    @Test
    fun `getDouble throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getDouble("missing")
        }
    }

    @Test
    fun `getDoubleOrDefault returns value when present`() {
        val data = Data.Builder()
            .putDouble("key", 2.5)
            .build()

        assertEquals(2.5, data.getDoubleOrDefault("key", 0.0))
    }

    @Test
    fun `getDoubleOrDefault returns default when absent`() {
        val data = Data.Builder().build()

        assertEquals(7.7, data.getDoubleOrDefault("missing", 7.7))
    }

    // endregion

    // region getBoolean / getBooleanOrDefault

    @Test
    fun `getBoolean throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getBoolean("missing")
        }
    }

    @Test
    fun `getBooleanOrDefault returns value when present`() {
        val data = Data.Builder()
            .putBoolean("key", true)
            .build()

        assertTrue(data.getBooleanOrDefault("key", false))
    }

    @Test
    fun `getBooleanOrDefault returns default when absent`() {
        val data = Data.Builder().build()

        assertTrue(data.getBooleanOrDefault("missing", true))
    }

    // endregion

    // region Array getters throw for absent keys

    @Test
    fun `getStringArray throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getStringArray("missing")
        }
    }

    @Test
    fun `getIntegerArray throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getIntegerArray("missing")
        }
    }

    @Test
    fun `getLongArray throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getLongArray("missing")
        }
    }

    @Test
    fun `getFloatArray throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getFloatArray("missing")
        }
    }

    @Test
    fun `getDoubleArray throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getDoubleArray("missing")
        }
    }

    @Test
    fun `getBooleanArray throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getBooleanArray("missing")
        }
    }

    @Test
    fun `getByteArray throws for absent key`() {
        val data = Data.Builder().build()

        assertFailsWith<IllegalStateException> {
            data.getByteArray("missing")
        }
    }

    // endregion

    // region putBlobAsString / getStringAsBlob — Base64 roundtrip

    @Test
    fun `putBlobAsString and getStringAsBlob roundtrip`() {
        val original = byteArrayOf(10, 20, 30, 40, 50, 127, -128)
        val data = Data.Builder()
            .putBlobAsString("blob", original)
            .build()

        val decoded = data.getStringAsBlob("blob")
        assertNotNull(decoded)
        assertContentEquals(original, decoded)
    }

    @Test
    fun `putBlobAsString with null stores null string`() {
        val data = Data.Builder()
            .putBlobAsString("blob", null)
            .build()

        assertTrue(data.hasString("blob"))
        assertNull(data.getStringAsBlob("blob"))
    }

    @Test
    fun `getStringAsBlob with empty byte array roundtrips`() {
        val original = byteArrayOf()
        val data = Data.Builder()
            .putBlobAsString("blob", original)
            .build()

        val decoded = data.getStringAsBlob("blob")
        assertNotNull(decoded)
        assertContentEquals(original, decoded)
    }

    // endregion
}
