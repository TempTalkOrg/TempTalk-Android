package com.difft.android.chat.translate

import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.chat.translate.backend.TranslateBackendException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext





class FakeTranslateBackend : TranslateBackend {

    override var isSupported: Boolean = true

    

    
    var identifyResult: String? = null
    var identifyError: Throwable? = null
    var identifyCount = 0

    

    var translateResult: String = ""
    var translateError: Throwable? = null

    
    val translatedPairs = mutableListOf<String>()

    

    var modelOnDevice: Boolean = false
    var isModelDownloadedCount = 0

    
    var downloadOutcome: Outcome = Outcome.Never
    var downloadCount = 0

    

    
    val warmedPairs = mutableListOf<String>()
    var releaseCount = 0

    sealed interface Outcome {
        
        data object Never : Outcome
        data object Success : Outcome
        data class Fails(val kind: TranslateBackendException.Kind) : Outcome

        
        data class Gated(val gate: CompletableDeferred<Unit>) : Outcome

        



        data object Cancelled : Outcome

        




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
