package com.difft.android.call.session

import android.app.Application
import com.difft.android.base.user.UserManager
import com.difft.android.call.LCallToChatController
import com.difft.android.call.core.CallTlsProvider
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CallFeedbackManager
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.manager.CallStatisticsLogManager
import com.difft.android.call.manager.CallTimeoutManager
import com.difft.android.call.manager.CallVibrationManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.service.OngoingCallServiceController
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import javax.inject.Inject

/** The process-wide collaborators a [CallSession] needs; one field per injected singleton. */
@Suppress("LongParameterList") // DI bundle: the call graph's constructor params
class CallSessionDeps @Inject constructor(
    val application: Application,
    val callToChatController: LCallToChatController,
    val messageEncryptor: INewMessageContentEncryptor,
    val onGoingCallStateManager: OnGoingCallStateManager,
    val callDataManager: CallDataManager,
    val callVibrationManager: CallVibrationManager,
    val callRingtoneManager: CallRingtoneManager,
    val contactorCacheManager: ContactorCacheManager,
    val callFeedbackManager: CallFeedbackManager,
    val statisticsLogManager: CallStatisticsLogManager,
    val callTimeoutManager: CallTimeoutManager,
    val callTlsProvider: CallTlsProvider,
    @ChativeHttpClientModule.Chat val httpClient: dagger.Lazy<ChativeHttpClient>,
    val userManager: UserManager,
    val proxyConfigProvider: ProxyConfigProvider,
    val authTokenProvider: AuthTokenProvider,
    val ongoingCallService: OngoingCallServiceController,
)
