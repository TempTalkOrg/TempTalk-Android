package com.difft.android.chat.translate.backend

import com.difft.android.chat.translate.ModelId

/**
 * The single seam between the translation feature and the engine that actually performs it.
 *
 * Everything engine-specific — and therefore everything Google-proprietary — lives behind this
 * interface, implemented in `:app` and selected per channel flavor. `:chat` compiles and runs
 * unchanged on a build that ships no Google code, which is what makes the feature strippable
 * without editing any Kotlin source.
 *
 * [isSupported] is authoritative: a caller must never assume a translation can happen.
 */
interface TranslateBackend {

    /** False on a build that ships no translation engine; every other member is then inert. */
    val isSupported: Boolean

    /**
     * BCP-47 code of [text]'s language, already normalised to a code [translate] accepts.
     *
     * @return null when the engine reached no conclusion — undetermined, below its confidence
     * threshold, or a language it cannot translate from. The caller decides what to do with that.
     */
    suspend fun identifyLanguage(text: String): String?

    /**
     * Translates [text] from [sourceCode] to [targetCode], both BCP-47.
     *
     * Deliberately does NOT wrap engine exceptions: the caller only reports "failed", and the
     * original exception type is the one diagnostic that says which part of the engine gave up.
     */
    suspend fun translate(text: String, sourceCode: String, targetCode: String): String

    suspend fun isModelDownloaded(model: ModelId): Boolean

    /**
     * Suspends until [model] is on the device.
     *
     * @throws TranslateBackendException carrying the reason the download did not finish. This is the
     * one path that classifies, because the caller turns the reason into a retry policy.
     */
    suspend fun downloadModel(model: ModelId)

    /**
     * Pre-creates whatever the engine needs to identify a language and to translate the
     * [sourceCode] -> [targetCode] pair, so the first real call is not slow.
     */
    suspend fun warmUp(sourceCode: String, targetCode: String)

    /** Closes every cached client. The next call rebuilds what it needs. */
    fun releaseClients()
}

/** Engine-independent reason a [TranslateBackend] call did not produce a result. */
class TranslateBackendException(
    val kind: Kind,
    cause: Throwable? = null,
) : Exception("translate backend failed: $kind", cause) {

    enum class Kind {
        NETWORK,
        NO_SPACE,
        DOWNLOAD_MANAGER_DISABLED,

        /**
         * The engine abandoned the call without a result and without an error. Reserved for a
         * backend that cannot express this as a coroutine cancellation; the ML Kit backend can, and
         * does, so it never raises this kind.
         */
        CANCELLED,

        /** This build ships no translation engine. */
        UNSUPPORTED,

        OTHER,
    }
}
