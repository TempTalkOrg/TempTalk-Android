package com.difft.android.chat.translate

import com.difft.android.base.utils.appScope
import com.difft.android.chat.translate.backend.TranslateLanguageCodes
import com.difft.android.chat.translate.model.TranslateModelStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * [TranslateEngine] decision order and result mapping.
 *
 * The engine's only outside surface is [FakeTranslateBackend]: the translation engine itself lives
 * in `:app` behind that interface, so these cases script identification and translation answers
 * directly instead of mocking ML Kit's static factories. Client caching and BCP-47 normalisation are
 * the backend's behaviour and are covered by the `:app` backend tests.
 *
 * What each case asserts:
 *  - T8   AUTO rejects a source outside the zh/en whitelist without calling the translator
 *  - T8b  MANUAL accepts any source language the backend can handle
 *  - T9   source == target short-circuits to SameLanguage before the translator
 *  - T10  a backend that echoes the input back is also SameLanguage
 *  - T11  a missing model answers ModelNotReady BEFORE identification runs
 *  - T12  blank and over-cap bodies map to Unsupported(EMPTY) / Unsupported(TOO_LONG)
 *  - T13  a backend exception maps to Failed carrying the cause
 *  - T13b "no conclusion" from the backend falls back to the character ratio
 *  - T57  warmUp asks the backend for exactly the pair the target needs
 */
class TranslateEngineTest {

    private lateinit var modelStore: TranslateModelStore
    private lateinit var backend: FakeTranslateBackend
    private lateinit var scope: CoroutineScope
    private lateinit var engine: TranslateEngine

    @Before
    fun setUp() {
        modelStore = mockk(relaxed = true)
        every { modelStore.requiredModels(any()) } returns setOf(ModelId(TranslateLanguageCodes.ZH))
        every { modelStore.isReady(any<TranslateLang>()) } returns true
        every { modelStore.isReady(any<ModelId>()) } returns true

        backend = FakeTranslateBackend()

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns scope

        engine = TranslateEngine(modelStore, backend) { mockk(relaxed = true) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkAll()
    }

    // --- T12 ----------------------------------------------------------------

    @Test
    fun `T12 a blank body is Unsupported EMPTY and never reaches the backend`() = runTest {
        val result = engine.translate("   ", TranslateLang.ZH, TranslateMode.AUTO)

        assertEquals(TranslateResult.Unsupported(TranslateResult.Reason.EMPTY), result)
        assertEquals(0, backend.identifyCount)
        assertTrue(backend.translatedPairs.isEmpty())
    }

    @Test
    fun `T12 a body over the cap is Unsupported TOO_LONG`() = runTest {
        val result = engine.translate("a".repeat(4097), TranslateLang.ZH, TranslateMode.AUTO)

        assertEquals(TranslateResult.Unsupported(TranslateResult.Reason.TOO_LONG), result)
        assertEquals(0, backend.identifyCount)
    }

    // --- T11 ----------------------------------------------------------------

    @Test
    fun `T11 a missing target model answers ModelNotReady before identification`() = runTest {
        every { modelStore.isReady(TranslateLang.ZH) } returns false

        val result = engine.translate("hello", TranslateLang.ZH, TranslateMode.AUTO)

        assertEquals(TranslateResult.ModelNotReady, result)
        assertEquals(0, backend.identifyCount)
        assertTrue(backend.translatedPairs.isEmpty())
    }

    @Test
    fun `T11 AUTO never asks the store to download, MANUAL does`() = runTest {
        every { modelStore.isReady(TranslateLang.ZH) } returns false

        engine.translate("hello", TranslateLang.ZH, TranslateMode.AUTO)
        verify(exactly = 0) { modelStore.ensureDownloaded(any<ModelId>(), any()) }

        engine.translate("hello", TranslateLang.ZH, TranslateMode.MANUAL)
        verify(exactly = 1) {
            modelStore.ensureDownloaded(ModelId(TranslateLanguageCodes.ZH), any())
        }
    }

    // --- T8 / T8b -----------------------------------------------------------

    @Test
    fun `T8 AUTO rejects a source outside the zh en whitelist`() = runTest {
        backend.identifyResult = "de"

        val result = engine.translate("guten tag", TranslateLang.ZH, TranslateMode.AUTO)

        assertEquals(
            TranslateResult.Unsupported(TranslateResult.Reason.LANGUAGE_NOT_SUPPORTED),
            result,
        )
        assertTrue(backend.translatedPairs.isEmpty())
    }

    @Test
    fun `T8b MANUAL translates from any source language`() = runTest {
        backend.identifyResult = "de"
        backend.translateResult = "你好"

        val result = engine.translate("guten tag", TranslateLang.ZH, TranslateMode.MANUAL)

        // sourceCode is the identified source the engine actually built the pair from.
        assertEquals(TranslateResult.Translated("你好", "de"), result)
        assertEquals(listOf("de-zh"), backend.translatedPairs)
    }

    @Test
    fun `a non-english source whose model is missing is ModelNotReady`() = runTest {
        backend.identifyResult = "de"
        every { modelStore.isReady(ModelId("de")) } returns false

        val result = engine.translate("guten tag", TranslateLang.ZH, TranslateMode.MANUAL)

        assertEquals(TranslateResult.ModelNotReady, result)
        assertTrue(backend.translatedPairs.isEmpty())
    }

    @Test
    fun `a pivot-language source is never gated on a model, since it is always available`() = runTest {
        backend.identifyResult = TranslateLanguageCodes.PIVOT
        backend.translateResult = "你好"
        every { modelStore.isReady(any<ModelId>()) } returns false
        every { modelStore.isReady(any<TranslateLang>()) } returns true

        val result = engine.translate("hello", TranslateLang.ZH, TranslateMode.AUTO)

        assertEquals(TranslateResult.Translated("你好", TranslateLanguageCodes.PIVOT), result)
    }

    // --- T9 / T10 -----------------------------------------------------------

    @Test
    fun `T9 an identified source equal to the target short-circuits to SameLanguage`() = runTest {
        backend.identifyResult = TranslateLanguageCodes.ZH

        val result = engine.translate("你好", TranslateLang.ZH, TranslateMode.AUTO)

        assertEquals(TranslateResult.SameLanguage, result)
        assertTrue(backend.translatedPairs.isEmpty())
    }

    @Test
    fun `T10 a backend echoing the input back is SameLanguage`() = runTest {
        backend.identifyResult = TranslateLanguageCodes.EN
        backend.translateResult = "  hello  "

        val result = engine.translate("hello", TranslateLang.ZH, TranslateMode.AUTO)

        assertEquals(TranslateResult.SameLanguage, result)
    }

    // --- T13 ----------------------------------------------------------------

    @Test
    fun `T13 a backend failure maps to Failed carrying the cause`() = runTest {
        backend.identifyResult = TranslateLanguageCodes.EN
        backend.translateError = IOException("boom")

        val result = engine.translate("hello", TranslateLang.ZH, TranslateMode.AUTO)

        assertTrue(result is TranslateResult.Failed)
        assertTrue((result as TranslateResult.Failed).cause is IOException)
    }

    @Test
    fun `an identification failure falls back to the character ratio instead of failing`() = runTest {
        backend.identifyError = IOException("identify down")
        backend.translateResult = "hi"

        val result = engine.translate("哈哈哈", TranslateLang.EN, TranslateMode.AUTO)

        assertEquals(TranslateResult.Translated("hi", TranslateLanguageCodes.ZH), result)
    }

    // --- T13b ---------------------------------------------------------------

    @Test
    fun `T13b no conclusion from the backend falls back to the character ratio`() = runTest {
        backend.identifyResult = null
        backend.translateResult = "hi"

        val result = engine.translate("哈哈哈", TranslateLang.EN, TranslateMode.AUTO)

        assertEquals(TranslateResult.Translated("hi", TranslateLanguageCodes.ZH), result)
    }

    @Test
    fun `T13b text with no zh or en letter has no conclusion`() = runTest {
        backend.identifyResult = null

        val result = engine.translate("12345 !!!", TranslateLang.ZH, TranslateMode.MANUAL)

        assertEquals(
            TranslateResult.Unsupported(TranslateResult.Reason.LANGUAGE_NOT_SUPPORTED),
            result,
        )
        assertTrue(backend.translatedPairs.isEmpty())
    }

    // --- warmUp (T57) -------------------------------------------------------

    @Test
    fun `T57 warmUp asks the backend for exactly the pair the target needs`() = runTest {
        engine.warmUp(TranslateLang.ZH)

        assertEquals(listOf("en-zh"), backend.warmedPairs)
    }

    @Test
    fun `T57 warmUp prepares the pair a later translation actually uses`() = runTest {
        backend.identifyResult = TranslateLanguageCodes.EN
        backend.translateResult = "你好"

        engine.warmUp(TranslateLang.ZH)
        engine.translate("hello", TranslateLang.ZH, TranslateMode.AUTO)

        assertEquals(backend.warmedPairs, backend.translatedPairs)
    }
}
