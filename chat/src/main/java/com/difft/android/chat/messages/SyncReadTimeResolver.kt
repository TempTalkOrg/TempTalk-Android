package com.difft.android.chat.messages


object SyncReadTimeResolver {

    
    
    private const val MIN_PLAUSIBLE_READ_AT_MS = 1_600_000_000_000L

    







    fun resolveSyncReadAt(payloadReadAt: Long, envelopeServerTimestamp: Long, fallback: Long): Long =
        if (payloadReadAt < MIN_PLAUSIBLE_READ_AT_MS || envelopeServerTimestamp <= 0L) fallback
        else minOf(payloadReadAt, envelopeServerTimestamp)
}
