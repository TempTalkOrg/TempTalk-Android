package com.difft.android.websocket.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the constructor-parameter vs Optional-property name-shadowing in ServiceResponse: the init
 * invariant check must operate on the raw nullable params, not the never-null Optional properties.
 * If it resolved to the properties, forResult() would throw IllegalArgumentException.
 */
class ServiceResponseConstructTest {

    @Test
    fun `forResult builds a success response without throwing`() {
        val r = ServiceResponse.forResult("ok", 200, "body")
        assertTrue(r.result.isPresent)
        assertEquals("ok", r.result.get())
        assertFalse(r.applicationError.isPresent)
        assertFalse(r.executionError.isPresent)
    }

    @Test
    fun `forApplicationError builds an error response without throwing`() {
        val r = ServiceResponse.forApplicationError<String>(RuntimeException("boom"), 500, null)
        assertFalse(r.result.isPresent)
        assertTrue(r.applicationError.isPresent)
    }
}
