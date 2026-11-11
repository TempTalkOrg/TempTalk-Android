package com.difft.android.chat.translate

import android.content.ComponentCallbacks2
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.chat.translate.backend.TranslateLanguageCodes
import com.difft.android.chat.translate.model.DownloadTrigger
import com.difft.android.chat.translate.model.TranslateModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
















@Singleton
class TranslateEngine @Inject constructor(
    private val modelStore: TranslateModelStore,
    private val backend: TranslateBackend,
    
    
    private val queue: dagger.Lazy<TranslateQueue>,
) {
    private var idleReleaseJob: Job? = null

    








    suspend fun translate(text: String, target: TranslateLang, mode: TranslateMode): TranslateResult {
        if (text.isBlank()) return TranslateResult.Unsupported(TranslateResult.Reason.EMPTY)
        if (text.length > MAX_TEXT_LENGTH) return TranslateResult.Unsupported(TranslateResult.Reason.TOO_LONG)

        if (!modelStore.isReady(target)) {
            val missing = modelStore.requiredModels(target)
            requestModels(missing, mode)
            return TranslateResult.ModelNotReady(missing)
        }

        armIdleRelease()
        val source = detectSource(text)
            ?: if (mode == TranslateMode.MANUAL) {
                TranslateLanguageCodes.PIVOT
            } else {
                return TranslateResult.Unsupported(TranslateResult.Reason.LANGUAGE_NOT_SUPPORTED)
            }
        if (mode == TranslateMode.AUTO && source !in AUTO_SOURCES) {
            return TranslateResult.Unsupported(TranslateResult.Reason.LANGUAGE_NOT_SUPPORTED)
        }
        if (source == target.code) return sameLanguage(text, source, mode)
        
        
        if (source != TranslateLanguageCodes.PIVOT && !modelStore.isReady(ModelId(source))) {
            val missing = setOf(ModelId(source))
            requestModels(missing, mode)
            return TranslateResult.ModelNotReady(missing)
        }

        return try {
            val translated = backend.translate(text, source, target.code)
            
            if (translated.trim() == text.trim()) {
                sameLanguage(text, source, mode)
            } else {
                TranslateResult.Translated(translated, source)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[Translate] engine error source=$source target=${target.code}: ${e.stackTraceToString()}" }
            TranslateResult.Failed(e)
        }
    }

    
    suspend fun warmUp(target: TranslateLang) {
        try {
            armIdleRelease()
            val source = if (target == TranslateLang.ZH) TranslateLang.EN else TranslateLang.ZH
            backend.warmUp(source.code, target.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[Translate] warmUp failed target=${target.code}: ${e.stackTraceToString()}" }
        }
    }

    



    suspend fun releaseAll() {
        queue.get().barrier { backend.releaseClients() }
    }

    
    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        idleReleaseJob?.cancel()
        appScope.launch { releaseAll() }
    }

    
    private fun sameLanguage(text: String, source: String, mode: TranslateMode): TranslateResult =
        if (mode == TranslateMode.MANUAL) TranslateResult.Translated(text, source) else TranslateResult.SameLanguage

    private fun requestModels(models: Set<ModelId>, mode: TranslateMode) {
        if (mode != TranslateMode.MANUAL) return
        
        
        models.forEach { modelStore.ensureDownloaded(it, DownloadTrigger.MANUAL_TRANSLATE) }
    }

    




    private suspend fun detectSource(text: String): String? {
        val code = try {
            backend.identifyLanguage(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[Translate] identify failed len=${text.length}: ${e.stackTraceToString()}" }
            null
        }
        return code ?: guessByCharRatio(text)
    }

    



    private fun guessByCharRatio(text: String): String? {
        var chinese = 0
        var english = 0
        for (char in text) {
            if (char in CHINESE_RANGE) {
                chinese++
            } else if (char in 'a'..'z' || char in 'A'..'Z') {
                english++
            }
        }
        val total = chinese + english
        if (total == 0) return null
        return if (chinese.toDouble() / total > CHAR_RATIO_THRESHOLD) {
            TranslateLanguageCodes.ZH
        } else {
            TranslateLanguageCodes.EN
        }
    }

    




    private fun armIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = appScope.launch {
            delay(IDLE_RELEASE_MS)
            releaseAll()
        }
    }

    companion object {
        
        private const val MAX_TEXT_LENGTH = 4096
        private const val CHAR_RATIO_THRESHOLD = 0.5
        private const val IDLE_RELEASE_MS = 5 * 60 * 1000L
        private val CHINESE_RANGE = '一'..'龥'
        private val AUTO_SOURCES = setOf(TranslateLanguageCodes.ZH, TranslateLanguageCodes.EN)
    }
}
