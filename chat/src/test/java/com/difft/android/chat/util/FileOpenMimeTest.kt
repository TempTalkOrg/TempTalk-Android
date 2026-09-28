package com.difft.android.chat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FileOpenMime], the pure decision logic behind the ACTION_VIEW retry in
 * `Context.viewUri`.
 *
 * A `.md` attachment resolves to `text/markdown`. Few apps declare it for ACTION_VIEW, and those
 * that do (file managers, app stores) commonly cannot render it and fail silently, while
 * `text/plain` has several real text viewers on a typical device. Plain-text formats without a
 * dedicated ecosystem therefore try `text/plain` first; text formats with real viewers (csv, json,
 * html, ...) keep their exact MIME first and use `text/plain` as the retry. Everything else is tried
 * exactly once so the user gets the "no app can open this" toast instead of silent nothing.
 */
class FileOpenMimeTest {

    @Test
    fun `markdown opens as text-plain first and retries with its exact mime`() {
        assertEquals(listOf("text/plain", "text/markdown"), FileOpenMime.candidates("text/markdown", "md"))
        assertEquals(listOf("text/plain", "text/x-log"), FileOpenMime.candidates("text/x-log", "log"))
    }

    @Test
    fun `text formats with real viewers keep their exact mime first`() {
        assertEquals(listOf("text/csv", "text/plain"), FileOpenMime.candidates("text/csv", "csv"))
        assertEquals(listOf("application/json", "text/plain"), FileOpenMime.candidates("application/json", "json"))
        assertEquals(listOf("text/html", "text/plain"), FileOpenMime.candidates("text/html", "html"))
    }

    @Test
    fun `text-plain mime is not duplicated`() {
        assertEquals(listOf("text/plain"), FileOpenMime.candidates("text/plain", "txt"))
    }

    @Test
    fun `binary mime is tried once`() {
        assertEquals(listOf("application/pdf"), FileOpenMime.candidates("application/pdf", "pdf"))
    }

    @Test
    fun `null mime with unknown extension stays null so the provider resolves the type`() {
        assertEquals(listOf<String?>(null), FileOpenMime.candidates(null, "bin"))
    }

    @Test
    fun `null mime with a text-like extension becomes text-plain`() {
        assertEquals(listOf("text/plain"), FileOpenMime.candidates(null, "log"))
    }

    @Test
    fun `extension matching tolerates case and a leading dot`() {
        assertEquals(listOf("text/plain", "text/markdown"), FileOpenMime.candidates("text/markdown", "MD"))
        assertEquals(listOf("text/plain", "text/markdown"), FileOpenMime.candidates("text/markdown", ".md"))
        assertTrue(FileOpenMime.isTextLike(".MD"))
    }

    @Test
    fun `a null extension is not text-like`() {
        assertFalse(FileOpenMime.isTextLike(null))
    }
}
