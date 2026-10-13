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

/**
 * Turns a piece of text into a [TranslateResult]. Stateless as far as the app is concerned: it
 * persists nothing, shows nothing and decides nothing about conversations — it only answers
 * "what does this text become in [TranslateLang]".
 *
 * Three boundaries are load-bearing:
 *  - It NEVER downloads a model. [TranslateModelStore] owns every download; the engine only asks
 *    [TranslateModelStore.isReady] and reports [TranslateResult.ModelNotReady] when the answer is
 *    no (in MANUAL mode it additionally asks the store to fetch what is missing, because that path
 *    is an explicit user action).
 *  - It is never called concurrently. [TranslateQueue] serialises every invocation, and client
 *    release runs as a queue barrier so a backend client is never closed mid-translation.
 *  - It knows nothing about the translation engine. Everything engine-specific sits behind
 *    [TranslateBackend], so this class compiles on a build that ships no translation engine at all.
 */
@Singleton
class TranslateEngine @Inject constructor(
    private val modelStore: TranslateModelStore,
    private val backend: TranslateBackend,
    // Lazy breaks the TranslateQueue <-> TranslateEngine construction cycle: the queue owns the
    // engine, and the engine needs the queue only to serialise releaseAll() behind a barrier.
    private val queue: dagger.Lazy<TranslateQueue>,
) {
    private var idleReleaseJob: Job? = null

    /**
     * Translates [text] into [target].
     *
     * AUTO is the per-conversation setting acting on incoming messages: it is restricted to
     * zh/en sources and silently skips same-language and undetermined text. MANUAL is an explicit
     * user action, so it always produces a visible result: it accepts any source language the
     * backend can translate from, treats undetermined text (digits, emoji) as English, and shows
     * same-language text as-is.
     */
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
        // The pivot language is always available to the backend, so it is never a missing model; any
        // other source has to be on the device before the pair can run.
        if (source != TranslateLanguageCodes.PIVOT && !modelStore.isReady(ModelId(source))) {
            val missing = setOf(ModelId(source))
            requestModels(missing, mode)
            return TranslateResult.ModelNotReady(missing)
        }

        return try {
            val translated = backend.translate(text, source, target.code)
            // A backend echoes the input back when it has nothing to do; treat that as same-language.
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

    /** Pre-creates the clients [target] needs so the first message is not slow. */
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

    /**
     * Closes every cached backend client. Runs as a queue barrier, so it can never close a client
     * the worker is using; the next translation rebuilds what it needs.
     */
    suspend fun releaseAll() {
        queue.get().barrier { backend.releaseClients() }
    }

    /** Forwarded from `TempTalkApplication.onTrimMemory`. */
    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        idleReleaseJob?.cancel()
        appScope.launch { releaseAll() }
    }

    /** AUTO skips same-language text; MANUAL shows the original, since the user asked for a result. */
    private fun sameLanguage(text: String, source: String, mode: TranslateMode): TranslateResult =
        if (mode == TranslateMode.MANUAL) TranslateResult.Translated(text, source) else TranslateResult.SameLanguage

    private fun requestModels(models: Set<ModelId>, mode: TranslateMode) {
        if (mode != TranslateMode.MANUAL) return
        // The one path allowed to pull a model outside the settings screen: the user asked for this
        // translation, so the download is started here; the manual caller waits for it and retries once.
        models.forEach { modelStore.ensureDownloaded(it, DownloadTrigger.MANUAL_TRANSLATE) }
    }

    /**
     * The backend's identification, falling back to the character-ratio heuristic whenever it
     * reached no conclusion — including when it failed outright, which must not cost the user a
     * translation the heuristic can still answer.
     */
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

    /**
     * Chinese/English share of the letters in [text]. Returns null when the text carries no letter
     * of either script (digits, emoji, punctuation), which is the "no conclusion" case.
     */
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

    /**
     * The backend clients are the only resident cost of this feature. They are cheap to rebuild, so
     * they go away after an idle window instead of living for the process. Armed on every path that
     * touches the backend, so a run that ends in a failure leaves nothing resident either.
     */
    private fun armIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = appScope.launch {
            delay(IDLE_RELEASE_MS)
            releaseAll()
        }
    }

    companion object {
        /** Mirrors the server-side limit the other clients enforce. */
        private const val MAX_TEXT_LENGTH = 4096
        private const val CHAR_RATIO_THRESHOLD = 0.5
        private const val IDLE_RELEASE_MS = 5 * 60 * 1000L
        private val CHINESE_RANGE = '一'..'龥'
        private val AUTO_SOURCES = setOf(TranslateLanguageCodes.ZH, TranslateLanguageCodes.EN)
    }
}
