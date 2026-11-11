package com.difft.android.chat.media

import difft.android.messageserialization.model.CONTENT_TYPE_LONG_TEXT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test






class EncryptedAttachmentProviderMimeTest {

    
    private val mustNotResolveExtension: () -> String? = {
        throw AssertionError("extension mime must not be resolved on this branch")
    }

    @Test
    fun `long text internal mime maps to text-plain`() {
        assertEquals(
            "text/plain",
            EncryptedAttachmentProvider.externalContentTypeFor(CONTENT_TYPE_LONG_TEXT, mustNotResolveExtension)
        )
    }

    @Test
    fun `a well-typed db mime is returned as-is without consulting the extension`() {
        assertEquals(
            "image/png",
            EncryptedAttachmentProvider.externalContentTypeFor("image/png", mustNotResolveExtension)
        )
        assertEquals(
            "application/pdf",
            EncryptedAttachmentProvider.externalContentTypeFor("application/pdf", mustNotResolveExtension)
        )
    }

    @Test
    fun `octet-stream db type falls back to the extension mime when known`() {
        assertEquals(
            "application/pdf",
            EncryptedAttachmentProvider.externalContentTypeFor("application/octet-stream") { "application/pdf" }
        )
    }

    @Test
    fun `octet-stream match is case-insensitive`() {
        assertEquals(
            "application/zip",
            EncryptedAttachmentProvider.externalContentTypeFor("Application/OCTET-Stream") { "application/zip" }
        )
    }

    @Test
    fun `octet-stream db type is kept when the extension is unknown`() {
        
        assertEquals(
            "application/octet-stream",
            EncryptedAttachmentProvider.externalContentTypeFor("application/octet-stream") { null }
        )
    }

    @Test
    fun `null db type falls back to the extension mime when known`() {
        assertEquals(
            "application/zip",
            EncryptedAttachmentProvider.externalContentTypeFor(null) { "application/zip" }
        )
    }

    @Test
    fun `blank db type falls back to the extension mime when known`() {
        assertEquals(
            "text/csv",
            EncryptedAttachmentProvider.externalContentTypeFor("   ") { "text/csv" }
        )
    }

    @Test
    fun `null db type with unknown extension stays null so getType can fall back`() {
        
        assertNull(EncryptedAttachmentProvider.externalContentTypeFor(null) { null })
    }

    @Test
    fun `long text takes precedence and never consults the extension`() {
        
        var consulted = false
        val result = EncryptedAttachmentProvider.externalContentTypeFor(CONTENT_TYPE_LONG_TEXT) {
            consulted = true
            "text/x-something"
        }
        assertEquals("text/plain", result)
        assertFalse("extension resolver must not be called for long text", consulted)
    }

    @Test
    fun `a real db type is preferred over the extension even when they differ`() {
        
        assertEquals(
            "audio/aac",
            EncryptedAttachmentProvider.externalContentTypeFor("audio/aac", mustNotResolveExtension)
        )
    }
}
