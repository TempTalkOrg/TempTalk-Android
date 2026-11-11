package com.difft.android.p2p.android.client

import com.difft.android.p2p.engine.upload.MigrationRejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test


class UploadTypedRejectionTest {

    @Test
    fun `a 403 carrying a receiver reject token is a typed rejection`() {
        assertEquals("insufficient-storage", SenderHttpClient.typedRejection(403, "insufficient-storage"))
        assertEquals("target-not-empty", SenderHttpClient.typedRejection(403, " target-not-empty\n"))
        for (reason in MigrationRejectReason.entries) {
            assertEquals(reason.wire, SenderHttpClient.typedRejection(403, reason.wire))
        }
    }

    @Test
    fun `anything without a receiver-asserted reason is not`() {
        assertNull(SenderHttpClient.typedRejection(403, "no active session"))
        assertNull(SenderHttpClient.typedRejection(403, ""))
        assertNull(SenderHttpClient.typedRejection(403, "<html>forbidden</html>"))
        assertNull(SenderHttpClient.typedRejection(500, ""))
        assertNull(SenderHttpClient.typedRejection(500, "insufficient-storage"))
        assertNull(SenderHttpClient.typedRejection(400, "insufficient-storage"))
        assertNull(SenderHttpClient.typedRejection(502, "Bad Gateway"))
    }
}
