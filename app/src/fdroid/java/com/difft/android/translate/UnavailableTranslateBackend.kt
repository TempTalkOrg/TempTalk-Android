package com.difft.android.translate

import com.difft.android.chat.translate.ModelId
import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.chat.translate.backend.TranslateBackendException
import javax.inject.Inject

/**
 * The backend for a build that ships no translation engine (F-Droid, which may carry no Google
 * proprietary code).
 *
 * It reports [isSupported] false, which every caller in `:chat` honours by not offering the feature
 * at all: the settings rows, the message actions and the automatic pass are all hidden or inert. The
 * throwing members therefore exist for a caller that ignored that contract — they fail loudly rather
 * than silently pretending a translation happened.
 */
class UnavailableTranslateBackend @Inject constructor() : TranslateBackend {

    override val isSupported: Boolean = false

    override suspend fun identifyLanguage(text: String): String? = null

    override suspend fun translate(text: String, sourceCode: String, targetCode: String): String =
        throw TranslateBackendException(TranslateBackendException.Kind.UNSUPPORTED)

    override suspend fun isModelDownloaded(model: ModelId): Boolean = false

    override suspend fun downloadModel(model: ModelId): Unit =
        throw TranslateBackendException(TranslateBackendException.Kind.UNSUPPORTED)

    override suspend fun warmUp(sourceCode: String, targetCode: String) = Unit

    override fun releaseClients() = Unit
}
