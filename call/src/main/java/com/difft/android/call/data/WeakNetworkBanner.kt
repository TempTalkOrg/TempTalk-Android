package com.difft.android.call.data

import com.difft.android.call.network.NetworkQualityView


enum class WeakNetworkBanner {
    NONE,

    
    LOCAL,

    
    REMOTE;

    companion object {
        


















        fun resolve(view: NetworkQualityView, participantCount: Int): WeakNetworkBanner = when {
            view.localIsBad -> LOCAL
            participantCount == ONE_ON_ONE_PARTICIPANT_COUNT &&
                view.badRemoteIdentities.isNotEmpty() -> REMOTE
            else -> NONE
        }

        
        private const val ONE_ON_ONE_PARTICIPANT_COUNT = 2
    }
}
