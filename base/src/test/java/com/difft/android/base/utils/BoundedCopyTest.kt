package com.difft.android.base.utils

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedCopyTest {

    @Test
    fun `copies a stream under the limit completely`() {
        val data = ByteArray(10_000) { it.toByte() }
        val out = ByteArrayOutputStream()

        val copied = ByteArrayInputStream(data).copyToBounded(out, maxBytes = 20_000)

        assertEquals(10_000L, copied)
        assertContentEquals(data, out.toByteArray())
    }

    @Test
    fun `a stream of exactly the limit is accepted`() {
        val out = ByteArrayOutputStream()

        val copied = ByteArrayInputStream(ByteArray(DEFAULT_BUFFER_SIZE * 3)).copyToBounded(out, maxBytes = DEFAULT_BUFFER_SIZE * 3L)

        assertEquals(DEFAULT_BUFFER_SIZE * 3L, copied)
    }

    @Test
    fun `a stream longer than the limit fails without writing past it`() {
        
        val maxBytes = 5_000L
        val out = ByteArrayOutputStream()

        val error = assertFailsWith<SizeLimitExceededException> {
            ByteArrayInputStream(ByteArray(50_000)).copyToBounded(out, maxBytes)
        }

        assertEquals(maxBytes, error.maxBytes)
        assertTrue(out.size() <= maxBytes, "wrote ${out.size()} bytes past the $maxBytes limit")
    }

    @Test
    fun `one byte over the limit fails`() {
        assertFailsWith<SizeLimitExceededException> {
            ByteArrayInputStream(ByteArray(101)).copyToBounded(ByteArrayOutputStream(), maxBytes = 100)
        }
    }
}
