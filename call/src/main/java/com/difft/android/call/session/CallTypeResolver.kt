package com.difft.android.call.session

import com.difft.android.base.call.CallType








internal object CallTypeResolver {

    
    const val ONE_ON_ONE_MAX_PARTICIPANTS = 2

    











    fun resolve(
        serverCallType: String?,
        participantCount: Int,
        localCallType: String,
        currentCallType: String,
    ): CallType? {
        if (currentCallType == CallType.INSTANT.type) return CallType.INSTANT

        val base = serverCallType?.takeIf { it.isNotBlank() }
            ?: currentCallType.takeIf { it.isNotBlank() }
            ?: return null

        return when (CallType.fromString(base)) {
            CallType.ONE_ON_ONE ->
                if (participantCount <= ONE_ON_ONE_MAX_PARTICIPANTS) CallType.ONE_ON_ONE
                else CallType.INSTANT

            
            
            CallType.GROUP ->
                if (localCallType == CallType.GROUP.type) CallType.GROUP
                else CallType.INSTANT

            CallType.INSTANT -> CallType.INSTANT

            
            
            null -> if (serverCallType.isNullOrBlank()) null else CallType.INSTANT
        }
    }
}
