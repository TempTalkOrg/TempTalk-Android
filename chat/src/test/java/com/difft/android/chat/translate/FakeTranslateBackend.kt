package com.difft.android.chat.translate

import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.chat.translate.backend.TranslateBackendException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/**
 * Scriptable [TranslateBackend] for `:chat` tests.
 *
 * It replaces the ML Kit static-factory mocking these tests used to need: the engine and the model
 * store now talk to this interface, so nothing in `:chat` — production or test — depends on Google
 * code, which is the property the F-Droid channel relies on.
 */
class FakeTranslateBackend : TranslateBackend {

    override var isSupported: Boolean = true

    // --- identifyLanguage ---------------------------------------------------

    /** The answer. Null is "no conclusion", which is what drives the engine's char-ratio fallback. */
    var identifyResult: String? = null
    var identifyError: Throwable? = null
    var identifyCount = 0

    // --- translate ----------------------------------------------------------

    var translateResult: String = ""
    var translateError: Throwable? = null

    /** "$source-$target" for every [translate] call, in order. */
    val translatedPairs = mutableListOf<String>()

    // --- models -------------------------------------------------------------

    var modelOnDevice: Boolean = false
    var isModelDownloadedCount = 0

    /** How the next [downloadModel] ends. */
    var downloadOutcome: Outcome = Outcome.Never
    var downloadCount = 0

    // --- clients ------------------------------------------------------------

    /** "$source-$target" for every [warmUp] call, in order. */
    val warmedPairs = mutableListOf<String>()
    var releaseCount = 0

    sealed interface Outcome {
        /** Never resolves, so the caller's download stays in flight for the whole test. */
        data object Never : Outcome
        data object Success : Outcome
        data class Fails(val kind: TranslateBackendException.Kind) : Outcome

        /** Resolves when [gate] does: completing it succeeds, completing it exceptionally fails. */
        data class Gated(val gate: CompletableDeferred<Unit>) : Outcome

        /**
         * The engine abandoned the attempt. Surfaces as a coroutine cancellation, which is exactly
         * how the ML Kit backend reports a cancelled Task.
         */
        data object Cancelled : Outcome

        /**
         * Ignores coroutine cancellation and ends only when [gate] does — a backend Task that outlives
         * the attempt that awaited it. Complete the gate exceptionally to end in that error (a
         * [CancellationException] ends it as cancelled).
         */
        data class HeldUntil(val gate: CompletableDeferred<Unit>) : Outcome
    }

    override suspend fun identifyLanguage(text: String): String? {
        identifyCount++
        identifyError?.let { throw it }
        return identifyResult
    }

    override suspend fun translate(text: String, sourceCode: String, targetCode: String): String {
        translatedPairs += "$sourceCode-$targetCode"
        translateError?.let { throw it }
        return translateResult
    }

    override suspend fun isModelDownloaded(model: ModelId): Boolean {
        isModelDownloadedCount++
        return modelOnDevice
    }

    override suspend fun downloadModel(model: ModelId) {
        downloadCount++
        when (val outcome = downloadOutcome) {
            Outcome.Never -> awaitCancellation()
            Outcome.Success -> Unit
            Outcome.Cancelled -> throw CancellationException("backend abandoned the download")
            is Outcome.Fails -> throw TranslateBackendException(outcome.kind)
            is Outcome.Gated -> outcome.gate.await()
            is Outcome.HeldUntil -> withContext(NonCancellable) { outcome.gate.await() }
        }
    }

    override suspend fun warmUp(sourceCode: String, targetCode: String) {
        warmedPairs += "$sourceCode-$targetCode"
    }

    override fun releaseClients() {
        releaseCount++
    }
}
