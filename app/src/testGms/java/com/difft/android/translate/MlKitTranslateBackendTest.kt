package com.difft.android.translate

import android.text.TextUtils
import com.difft.android.chat.translate.backend.TranslateBackendException
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * [MlKitTranslateBackend]: the two things that are genuinely ML Kit's and therefore cannot be tested
 * from `:chat` — BCP-47 normalisation, and the error-code mapping that decides the store's retry
 * policy.
 *
 * Both ML Kit clients are created through static factories, so those are the seams: MockK static
 * mocks hand back fake clients whose GMS `Task`s resolve synchronously. `MlKitException`'s
 * constructor reaches into the Android framework for one null check, which a bare JVM unit test
 * answers with "not mocked" — hence the third static mock.
 */
class MlKitTranslateBackendTest {

    private lateinit var identifier: LanguageIdentifier
    private lateinit var translator: Translator
    private lateinit var backend: MlKitTranslateBackend

    private val translatorOptions = mutableListOf<TranslatorOptions>()

    @Before
    fun setUp() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }

        identifier = mockk(relaxed = true)
        translator = mockk(relaxed = true)

        mockkStatic(LanguageIdentification::class)
        every { LanguageIdentification.getClient(any()) } returns identifier

        mockkStatic(Translation::class)
        val optionsSlot = slot<TranslatorOptions>()
        every { Translation.getClient(capture(optionsSlot)) } answers {
            translatorOptions += optionsSlot.captured
            translator
        }

        backend = MlKitTranslateBackend()
    }

    @After
    fun tearDown() = unmockkAll()

    private fun identifiesAs(tag: String) {
        every { identifier.identifyLanguage(any()) } returns successTask(tag)
    }

    // --- identification normalisation ---------------------------------------

    @Test
    fun `zh-Hans and zh-Hant normalise to zh`() = runTest {
        for (tag in listOf("zh-Hans", "zh-Hant")) {
            identifiesAs(tag)

            assertEquals("tag=$tag", TranslateLanguage.CHINESE, backend.identifyLanguage("你好"))
        }
    }

    @Test
    fun `zh-Latn pinyin is treated as english, not chinese`() = runTest {
        identifiesAs("zh-Latn")

        assertEquals(TranslateLanguage.ENGLISH, backend.identifyLanguage("ni hao"))
    }

    @Test
    fun `und is no conclusion`() = runTest {
        identifiesAs(LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG)

        assertNull(backend.identifyLanguage("12345"))
    }

    @Test
    fun `a language ML Kit cannot translate from is no conclusion`() = runTest {
        identifiesAs("xx")

        assertNull(backend.identifyLanguage("whatever"))
    }

    // --- client caching -----------------------------------------------------

    @Test
    fun `clients are cached, so a second translation creates nothing new`() = runTest {
        every { translator.translate(any()) } returns successTask("你好")

        backend.translate("hello", "en", "zh")
        backend.translate("hello there", "en", "zh")

        assertEquals(1, translatorOptions.size)
    }

    @Test
    fun `warmUp creates exactly the pair a later translation reuses`() = runTest {
        every { translator.translate(any()) } returns successTask("你好")

        backend.warmUp("en", "zh")
        val warmed = translatorOptions.size
        backend.translate("hello", "en", "zh")

        verify(exactly = 1) { LanguageIdentification.getClient(any()) }
        assertEquals(warmed, translatorOptions.size)
    }

    @Test
    fun `releaseClients closes every cached client`() = runTest {
        every { translator.translate(any()) } returns successTask("你好")
        backend.warmUp("en", "zh")

        backend.releaseClients()

        verify(exactly = 1) { translator.close() }
        verify(exactly = 1) { identifier.close() }
    }

    // --- failure classification ---------------------------------------------

    @Test
    fun `network-ish error codes become NETWORK`() {
        val codes = listOf(
            MlKitException.NETWORK_ISSUE,
            MlKitException.UNAVAILABLE,
            MlKitException.DEADLINE_EXCEEDED,
        )
        codes.forEach { code ->
            assertEquals(
                "code=$code",
                TranslateBackendException.Kind.NETWORK,
                classifyMlKitFailure(MlKitException("boom", code)),
            )
        }
    }

    @Test
    fun `NOT_ENOUGH_SPACE becomes NO_SPACE`() {
        assertEquals(
            TranslateBackendException.Kind.NO_SPACE,
            classifyMlKitFailure(MlKitException("full", MlKitException.NOT_ENOUGH_SPACE)),
        )
    }

    /** A disabled/absent DownloadManager is reported only in the message, never in an error code. */
    @Test
    fun `a Download manager message wins over the error code`() {
        assertEquals(
            TranslateBackendException.Kind.DOWNLOAD_MANAGER_DISABLED,
            classifyMlKitFailure(
                MlKitException("Download manager is disabled", MlKitException.INTERNAL)
            ),
        )
    }

    @Test
    fun `anything else is OTHER`() {
        assertEquals(
            TranslateBackendException.Kind.OTHER,
            classifyMlKitFailure(MlKitException("odd", MlKitException.INTERNAL)),
        )
        assertEquals(
            TranslateBackendException.Kind.OTHER,
            classifyMlKitFailure(IOException("not an ML Kit exception at all")),
        )
    }

    // --- GMS Task fakes -----------------------------------------------------

    private fun <T> successTask(value: T): Task<T> {
        val task = mockk<Task<T>>(relaxed = true)
        every { task.isCanceled } returns false
        every { task.addOnSuccessListener(any<OnSuccessListener<in T>>()) } answers {
            firstArg<OnSuccessListener<in T>>().onSuccess(value)
            task
        }
        every { task.addOnFailureListener(any<OnFailureListener>()) } returns task
        every { task.addOnCompleteListener(any<OnCompleteListener<T>>()) } answers {
            firstArg<OnCompleteListener<T>>().onComplete(task)
            task
        }
        return task
    }
}
