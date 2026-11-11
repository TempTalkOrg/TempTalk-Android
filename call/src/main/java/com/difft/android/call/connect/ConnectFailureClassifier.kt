package com.difft.android.call.connect

import com.difft.android.call.exception.CallPreconditionException
import com.difft.android.call.exception.StartCallException
import com.google.protobuf.InvalidProtocolBufferException
import io.livekit.android.room.RoomException
import kotlinx.coroutines.CancellationException


internal enum class ConnectFailureCategory {
    
    TRANSIENT,

    
    SERVER_VERDICT,

    
    PRECONDITION,
}




internal fun classifyConnectFailure(t: Throwable): ConnectFailureCategory {
    
    
    
    generateSequence(t) { it.cause }.take(MAX_CAUSE_DEPTH).forEach { link ->
        when (link) {
            
            
            
            is RoomException.StartCallException, is StartCallException ->
                return ConnectFailureCategory.SERVER_VERDICT

            
            
            
            
            is CallPreconditionException, is InvalidProtocolBufferException, is Error ->
                return ConnectFailureCategory.PRECONDITION

            else -> Unit
        }
    }
    return ConnectFailureCategory.TRANSIENT
}


private const val MAX_CAUSE_DEPTH = 8






internal fun serverVerdictStatus(t: Throwable): Int? = (t as? RoomException.StartCallException)?.statusCode
