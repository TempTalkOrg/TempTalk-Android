package com.difft.android.chat.media

import difft.android.messageserialization.model.CONTENT_TYPE_LONG_TEXT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the externally-facing content-type decision
 * ([EncryptedAttachmentProvider.externalContentTypeFor]) that `getType()` serves to external
 * receivers (ACTION_VIEW / ACTION_SEND) and to `OpenableColumns`.
 *
 * The pure branching logic is extracted so it can be exercised without a ContentProvider, a DB, or
 * the Android `MimeTypeMap` — the extension mime is injected as a lambda. It encodes two corrections
 * introduced for encrypted-at-rest attachments:
 *  - **long text**: the internal `text/x-signal-plain` is mapped to `text/plain` so receivers don't
 *    fall back to a generic `.tmp` name (§13.6.3);
 *  - **generic files (P4)**: a missing / blank / `application/octet-stream` DB type falls back to the
 *    file-name-extension mime when known, mirroring the pre-encryption FileProvider behaviour so
 *    "open in / share to" external apps does not regress (§14.5).
 *
 * The lambda is also asserted to be **lazy**: a well-typed DB row must never trigger the (Android)
 * extension lookup.
 */
class EncryptedAttachmentProviderMimeTest {

    /** Throws if the extension mime is consulted — used to assert the lazy branch is not taken. */
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
        // No better guess than octet-stream — keep the DB type rather than inventing one.
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
        // getType() resolves this to mimeOf(fileName) (octet-stream) — keep it null here.
        assertNull(EncryptedAttachmentProvider.externalContentTypeFor(null) { null })
    }

    @Test
    fun `long text takes precedence and never consults the extension`() {
        // Even if an extension mime were available, long text must always be text/plain.
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
        // A trustworthy, descriptive DB mime wins; the extension is only a fallback for octet-stream.
        assertEquals(
            "audio/aac",
            EncryptedAttachmentProvider.externalContentTypeFor("audio/aac", mustNotResolveExtension)
        )
    }
}
