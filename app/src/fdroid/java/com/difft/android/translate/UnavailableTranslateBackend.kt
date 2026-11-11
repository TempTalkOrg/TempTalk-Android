package com.difft.android.translate

import com.difft.android.chat.translate.ModelId
import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.chat.translate.backend.TranslateBackendException
import javax.inject.Inject










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
