package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L






sealed class EnvelopeProcessResult {
    




    data class Success(val result: EnvelopToMessageProcessor.Result?) : EnvelopeProcessResult()

    
    data class PermanentFailure(val reason: DropReason, val cause: Throwable?) : EnvelopeProcessResult()

    
    data class TransientFailure(val cause: Throwable) : EnvelopeProcessResult()

    



    data object Paused : EnvelopeProcessResult()
}






enum class DropReason {
    
    DECRYPTION_FAILED,

    
    DECRYPTION_DATA_CORRUPT,

    
    MALFORMED_NOTIFY_JSON,

    
    BASE64_DECODE_FAILED,

    





    BUILD_ENVELOPE_FAILED,

    



    RETRY_EXHAUSTED,

    
    UNSUPPORTED_VERSION,
}













fun reportPermanentDrop(
    reason: DropReason,
    cause: Throwable?,
    envelopeTs: Long,
    tag: String,
) {
    L.w {
        "[Message][$tag] permanent drop ts=$envelopeTs reason=$reason: " +
            (cause?.stackTraceToString() ?: "no cause")
    }
}
