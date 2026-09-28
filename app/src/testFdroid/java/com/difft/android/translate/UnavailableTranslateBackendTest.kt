package com.difft.android.translate

import com.difft.android.chat.translate.ModelId
import com.difft.android.chat.translate.backend.TranslateBackendException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [UnavailableTranslateBackend]: the binding the F-Droid channel compiles.
 *
 * The point of these cases is that the channel's build has NO Google dependency to fall back on, so
 * the backend must answer every question without one and must never pretend a translation happened.
 */
class UnavailableTranslateBackendTest {

    private val backend = UnavailableTranslateBackend()

    @Test
    fun `it reports itself unsupported`() {
        assertFalse(backend.isSupported)
    }

    @Test
    fun `identification reaches no conclusion instead of guessing`() = runTest {
        assertNull(backend.identifyLanguage("hello"))
    }

    @Test
    fun `no model is ever on the device`() = runTest {
        assertFalse(backend.isModelDownloaded(ModelId("zh")))
    }

    @Test
    fun `translate fails loudly rather than echoing the input back`() = runTest {
        val error = runCatching { backend.translate("hello", "en", "zh") }.exceptionOrNull()

        assertEquals(
            TranslateBackendException.Kind.UNSUPPORTED,
            (error as TranslateBackendException).kind,
        )
    }

    @Test
    fun `downloading a model fails loudly`() = runTest {
        val error = runCatching { backend.downloadModel(ModelId("zh")) }.exceptionOrNull()

        assertEquals(
            TranslateBackendException.Kind.UNSUPPORTED,
            (error as TranslateBackendException).kind,
        )
    }

    @Test
    fun `warmUp and releaseClients are inert`() = runTest {
        backend.warmUp("en", "zh")
        backend.releaseClients()
    }
}
