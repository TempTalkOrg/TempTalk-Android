package com.difft.android.translate

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.translate.ModelId
import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.chat.translate.backend.TranslateBackendException
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Google ML Kit implementation of [TranslateBackend]. The ONLY place in the app that names ML Kit,
 * and it lives in a source set the F-Droid channel does not compile — which is what lets that
 * channel drop the dependency without a single Kotlin edit anywhere else.
 *
 * Clients are guarded by a plain monitor rather than a `Mutex`: nothing inside a critical section
 * suspends (ML Kit's `getClient` and `close` are synchronous), and [releaseClients] is not a
 * suspending function.
 */
class MlKitTranslateBackend @Inject constructor() : TranslateBackend {

    private val modelManager: RemoteModelManager by lazy { RemoteModelManager.getInstance() }

    private val clientLock = Any()
    private var identifier: LanguageIdentifier? = null
    private val translators = mutableMapOf<String, Translator>()

    override val isSupported: Boolean = true

    override suspend fun identifyLanguage(text: String): String? {
        val tag = getOrCreateIdentifier().identifyLanguage(text).awaitResult()
        if (tag == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) return null
        return toTranslateCode(tag)
    }

    override suspend fun translate(text: String, sourceCode: String, targetCode: String): String =
        getOrCreateTranslator(sourceCode, targetCode).translate(text).awaitResult()

    override suspend fun isModelDownloaded(model: ModelId): Boolean =
        modelManager.isModelDownloaded(remoteModel(model)).awaitResult()

    override suspend fun downloadModel(model: ModelId) {
        try {
            modelManager.download(remoteModel(model), DownloadConditions.Builder().build()).awaitResult()
        } catch (e: CancellationException) {
            // ML Kit ends some failures by cancelling the Task; awaitResult turns that into a
            // coroutine cancellation, which the caller already treats as "attempt abandoned".
            throw e
        } catch (e: Exception) {
            throw TranslateBackendException(classifyMlKitFailure(e), e)
        }
    }

    override suspend fun warmUp(sourceCode: String, targetCode: String) {
        getOrCreateIdentifier()
        getOrCreateTranslator(sourceCode, targetCode)
    }

    override fun releaseClients() {
        synchronized(clientLock) {
            val count = translators.size
            translators.values.forEach { it.close() }
            translators.clear()
            identifier?.close()
            identifier = null
            if (count > 0) L.i { "[Translate] clients released translators=$count" }
        }
    }

    private fun getOrCreateIdentifier(): LanguageIdentifier = synchronized(clientLock) {
        identifier ?: LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
                .build()
        ).also { identifier = it }
    }

    private fun getOrCreateTranslator(source: String, target: String): Translator =
        synchronized(clientLock) {
            translators.getOrPut("$source-$target") {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build()
                )
            }
        }

    /** Raw BCP-47 identification, normalised to a code [TranslateLanguage] accepts. */
    private fun toTranslateCode(tag: String): String? = when {
        // Pinyin is Latin script; ML Kit's Chinese model cannot read it, English is the closer fit.
        tag.startsWith(CHINESE_LATIN_TAG, ignoreCase = true) -> TranslateLanguage.ENGLISH
        tag.startsWith(TranslateLanguage.CHINESE, ignoreCase = true) -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.fromLanguageTag(tag)
    }

    private fun remoteModel(model: ModelId): TranslateRemoteModel =
        TranslateRemoteModel.Builder(model.code).build()

    private companion object {
        const val CONFIDENCE_THRESHOLD = 0.5f
        const val CHINESE_LATIN_TAG = "zh-Latn"
    }
}

/**
 * ML Kit's failure vocabulary, expressed in the engine-independent one the store consumes.
 *
 * Top-level and internal so the mapping can be tested without an ML Kit client: it is the piece
 * that decides the retry policy, and it is the piece most likely to drift as the SDK changes.
 */
internal fun classifyMlKitFailure(e: Throwable): TranslateBackendException.Kind {
    // ML Kit reports a disabled/absent DownloadManager only in the message, not in an error code.
    if (e.message?.contains("Download manager", ignoreCase = true) == true) {
        return TranslateBackendException.Kind.DOWNLOAD_MANAGER_DISABLED
    }
    return when ((e as? MlKitException)?.errorCode) {
        MlKitException.NETWORK_ISSUE, MlKitException.UNAVAILABLE, MlKitException.DEADLINE_EXCEEDED ->
            TranslateBackendException.Kind.NETWORK

        MlKitException.NOT_ENOUGH_SPACE -> TranslateBackendException.Kind.NO_SPACE
        else -> TranslateBackendException.Kind.OTHER
    }
}
