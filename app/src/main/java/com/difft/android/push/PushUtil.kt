package com.difft.android.push

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class FcmInitResult {
    data object Idle : FcmInitResult()

    data object Loading : FcmInitResult()

    data class PlayServicesUnavailable(val statusCode: Int) : FcmInitResult()

    data class Success(val token: String) : FcmInitResult()

    
    data class Failure(val reason: String) : FcmInitResult()
}





@Singleton

enum class ResyncResult { OK, SKIP, FAIL }

class PushUtil @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val _fcmInitResult = MutableStateFlow<FcmInitResult>(FcmInitResult.Idle)
    val fcmInitResult: StateFlow<FcmInitResult> = _fcmInitResult.asStateFlow()

    private var dialogShownInSession = false

    fun canShowFcmUnavailableDialog(): Boolean = !dialogShownInSession

    fun markFcmUnavailableDialogShown() {
        dialogShownInSession = true
    }

    fun initFCMPush() {
        L.d { "[Push] FCM not available in this build, will prompt background connection" }
        
        _fcmInitResult.value = FcmInitResult.PlayServicesUnavailable(0)
    }

    
    suspend fun resyncBind(): ResyncResult = ResyncResult.SKIP

    suspend fun sendRegistrationToServer(fcmID: String) {
        L.d { "[Push] FCM not available in this build" }
    }
}
