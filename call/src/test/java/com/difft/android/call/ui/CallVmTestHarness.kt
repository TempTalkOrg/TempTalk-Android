package com.difft.android.call.ui

import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.call.CallRole
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.session.CallSessionParams
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.network.UrlManager
import dagger.hilt.EntryPoints
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers












internal object CallVmTestHarness {

    private var pending: CallSessionTestHarness? = null

    
    lateinit var lastHarness: CallSessionTestHarness
        private set

    private fun harness(): CallSessionTestHarness = pending ?: CallSessionTestHarness(
        main = Dispatchers.Main.immediate,
        default = CallSessionTestHarness.parked(),
        captureCleanup = false,
    ).also { pending = it }

    



    fun mockConstructionCollaborators() {
        pending = null
    }

    
    fun stubCallStatus(callStatus: CallStatus, callType: String) {
        val components = harness().components
        components.callStatus.value = callStatus
        components.callType.value = callType
    }

    












    fun mockEntryPointAccessors(contactorCacheManager: ContactorCacheManager = mockk(relaxed = true)): ContactorCacheManager {
        mockkStatic(EntryPoints::class)
        val entryPoint = mockk<LCallManager.EntryPoint>(relaxed = true)
        every { entryPoint.contactorCacheManager } returns contactorCacheManager
        val globalEntryPoint = mockk<GlobalHiltEntryPoint>(relaxed = true)
        every { globalEntryPoint.myId } returns "self-uid"
        every { EntryPoints.get(any(), LCallManager.EntryPoint::class.java) } returns entryPoint
        every { EntryPoints.get(any(), GlobalHiltEntryPoint::class.java) } returns globalEntryPoint
        return contactorCacheManager
    }

    fun buildCallIntent(
        action: CallIntent.Action,
        callType: String,
        conversationId: String? = "test-conversation",
        roomName: String? = "Test Room",
    ): CallIntent {
        val context = ApplicationProvider.getApplicationContext<ScopeApplication>()
        val intent = CallIntent.Builder(context, LCallActivity::class.java)
            .withAction(action)
            .withCallType(callType)
            .withConversationId(conversationId)
            .withRoomName(roomName)
            .build()
        return CallIntent(intent)
    }

    fun buildViewModel(
        callIntent: CallIntent,
        callRole: CallRole = CallRole.CALLEE,
        contactorCacheManager: ContactorCacheManager = mockk(relaxed = true),
        urlManager: UrlManager = mockk(relaxed = true),
    ): LCallViewModel {
        val application = ApplicationProvider.getApplicationContext<ScopeApplication>()
        ApplicationHelper.init(application)
        val harness = harness().let { current ->
            if (current.contactorCacheManager === contactorCacheManager) current
            else current.withContactorCacheManager(contactorCacheManager)
        }
        pending = null
        lastHarness = harness
        return LCallViewModel(
            application = application,
            session = harness.start(CallSessionParams(false, callIntent, CallConfig(), callRole, VoicePreset.ORIGINAL)),
            userManager = mockk(relaxed = true),
            urlManager = urlManager,
            onGoingCallStateManager = harness.ogsm,
        )
    }

    
    private fun CallSessionTestHarness.withContactorCacheManager(
        contactorCacheManager: ContactorCacheManager,
    ): CallSessionTestHarness = CallSessionTestHarness(
        main = main,
        default = default,
        contactorCacheManager = contactorCacheManager,
        captureCleanup = false,
    ).also {
        it.components.callStatus.value = components.callStatus.value
        it.components.callType.value = components.callType.value
    }
}
