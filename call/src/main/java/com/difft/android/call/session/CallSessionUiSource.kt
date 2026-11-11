package com.difft.android.call.session

import com.difft.android.call.network.NetworkQualityView
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow






interface CallSessionUiSource {
    
    val speakingEnabled: StateFlow<Boolean>

    
    val isShareScreening: StateFlow<Boolean>

    
    val countDownDurationStr: StateFlow<String>

    
    val reconnectCount: StateFlow<Int>

    





    val networkQuality: StateFlow<NetworkQualityView>

    
    val groupCriticalAlert: StateFlow<Boolean>

    




    val events: SharedFlow<CallSessionUiEvent>
}
