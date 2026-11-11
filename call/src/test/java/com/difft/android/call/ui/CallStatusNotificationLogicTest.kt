package com.difft.android.call.ui

import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.CallIntent
import com.difft.android.call.R
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.MediaSendIssueState
import com.difft.android.call.data.WeakNetworkBanner
import com.difft.android.base.application.ScopeApplication
import com.difft.android.call.service.TestScopeApplication
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config





@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [30])
class CallStatusNotificationLogicTest {

    @Before
    fun setUp() {
        ApplicationHelper.init(ApplicationProvider.getApplicationContext<ScopeApplication>())
    }

    private fun intent(action: CallIntent.Action, callType: String): CallIntent =
        CallVmTestHarness.buildCallIntent(action, callType)

    private fun resolve(
        callStatus: CallStatus,
        callType: String = CallType.ONE_ON_ONE.type,
        action: CallIntent.Action = CallIntent.Action.START_CALL,
        callTimerRunning: Boolean = false,
        mediaSendIssue: MediaSendIssueState = MediaSendIssueState.NONE,
        
        weakNetwork: WeakNetworkBanner = WeakNetworkBanner.NONE,
    ): CallStatusNotification? = callStatusNotification(
        callStatus = callStatus,
        callType = callType,
        callIntent = intent(action, callType),
        callTimerRunning = callTimerRunning,
        mediaSendIssue = mediaSendIssue,
        weakNetwork = weakNetwork,
    )

    @Test
    fun `one-on-one CALLING stays out of the pill - the title owns waiting-for-answer`() {
        
        
        assertNull(resolve(CallStatus.CALLING))
    }

    @Test
    fun `one-on-one callee in CALLING also keeps the pill empty`() {
        
        
        assertNull(resolve(CallStatus.CALLING, action = CallIntent.Action.JOIN_CALL))
    }

    @Test
    fun `group joining shows the connecting label`() {
        val result = resolve(CallStatus.JOINING, callType = CallType.GROUP.type, action = CallIntent.Action.JOIN_CALL)
        assertEquals(ResUtils.getString(R.string.call_connecting_title), result?.text)
        assertTrue(result!!.spinning)
    }

    @Test
    fun `reconnecting shows the connecting label regardless of intent action`() {
        val result = resolve(CallStatus.RECONNECTING)
        assertEquals(ResUtils.getString(R.string.call_connecting_title), result?.text)
    }

    @Test
    fun `switching server shows the connecting label even for a one-on-one caller`() {
        
        val result = resolve(CallStatus.SWITCHING_SERVER)
        assertEquals(ResUtils.getString(R.string.call_connecting_title), result?.text)
        assertTrue(result!!.spinning)
    }

    @Test
    fun `reconnect failed on a joined call shows the disconnected label without spinner`() {
        val result = resolve(
            CallStatus.RECONNECT_FAILED, callType = CallType.GROUP.type, action = CallIntent.Action.JOIN_CALL,
        )
        assertEquals(ResUtils.getString(R.string.call_disconnected_title), result?.text)
        assertFalse(result!!.spinning)
    }

    @Test
    fun `reconnect failed for a one-on-one caller stays hidden`() {
        
        assertNull(resolve(CallStatus.RECONNECT_FAILED))
    }

    @Test
    fun `connected before the timer starts shows the connecting label`() {
        val result = resolve(CallStatus.CONNECTED, callTimerRunning = false)
        assertEquals(ResUtils.getString(R.string.call_connecting_title), result?.text)
    }

    @Test
    fun `connected with healthy uplink shows nothing`() {
        assertNull(resolve(CallStatus.CONNECTED, callTimerRunning = true))
    }

    @Test
    fun `connected with uplink-only degradation shows the media send issue label`() {
        
        val result = resolve(
            CallStatus.CONNECTED, callTimerRunning = true, mediaSendIssue = MediaSendIssueState.SEND_RECOVERING,
        )
        assertEquals(ResUtils.getString(R.string.call_media_send_issue), result?.text)
        assertTrue(result!!.spinning)
    }

    @Test
    fun `connected with whole-link recovery shows the connecting label`() {
        
        
        val result = resolve(
            CallStatus.CONNECTED, callTimerRunning = true, mediaSendIssue = MediaSendIssueState.CONNECTION_RECOVERING,
        )
        assertEquals(ResUtils.getString(R.string.call_connecting_title), result?.text)
        assertTrue(result!!.spinning)
    }

    @Test
    fun `media-ready gate outranks the media send issue`() {
        
        val result = resolve(
            CallStatus.CONNECTED, callTimerRunning = false, mediaSendIssue = MediaSendIssueState.SEND_RECOVERING,
        )
        assertEquals(ResUtils.getString(R.string.call_connecting_title), result?.text)
    }

    @Test
    fun `reconnected counts as connected`() {
        assertNull(resolve(CallStatus.RECONNECTED, callTimerRunning = true))
    }

    @Test
    fun `disconnected shows nothing`() {
        assertNull(resolve(CallStatus.DISCONNECTED, action = CallIntent.Action.JOIN_CALL))
    }

    
    
    

    @Test
    fun `connected with a bad local link shows the local weak-network hint without spinning`() {
        val result = resolve(CallStatus.CONNECTED, callTimerRunning = true, weakNetwork = WeakNetworkBanner.LOCAL)
        assertEquals(ResUtils.getString(R.string.call_myself_network_poor_tip), result?.text)
        assertEquals(CallStatusIcon.WEAK_NETWORK, result?.icon)
        assertFalse("weak network is a sustained state, not a recovery in progress", result!!.spinning)
    }

    @Test
    fun `connected with a bad remote link shows the remote weak-network hint without spinning`() {
        val result = resolve(CallStatus.CONNECTED, callTimerRunning = true, weakNetwork = WeakNetworkBanner.REMOTE)
        assertEquals(ResUtils.getString(R.string.call_other_network_poor_tip), result?.text)
        assertEquals(CallStatusIcon.WEAK_NETWORK, result?.icon)
        assertFalse(result!!.spinning)
    }

    @Test
    fun `the media send issue outranks the local weak-network hint`() {
        
        
        val result = resolve(
            CallStatus.CONNECTED,
            callTimerRunning = true,
            mediaSendIssue = MediaSendIssueState.SEND_RECOVERING,
            weakNetwork = WeakNetworkBanner.LOCAL,
        )
        assertEquals(ResUtils.getString(R.string.call_media_send_issue), result?.text)
    }

    @Test
    fun `the media send issue outranks the remote weak-network hint too`() {
        
        
        val result = resolve(
            CallStatus.CONNECTED,
            callTimerRunning = true,
            mediaSendIssue = MediaSendIssueState.SEND_RECOVERING,
            weakNetwork = WeakNetworkBanner.REMOTE,
        )
        assertEquals(ResUtils.getString(R.string.call_media_send_issue), result?.text)
    }

    @Test
    fun `the weak-network hint takes over the pill the moment the uplink recovers`() {
        
        
        val duringIssue = resolve(
            CallStatus.CONNECTED,
            callTimerRunning = true,
            mediaSendIssue = MediaSendIssueState.SEND_RECOVERING,
            weakNetwork = WeakNetworkBanner.LOCAL,
        )
        assertEquals(ResUtils.getString(R.string.call_media_send_issue), duringIssue?.text)

        val afterRecovery = resolve(
            CallStatus.CONNECTED,
            callTimerRunning = true,
            mediaSendIssue = MediaSendIssueState.NONE,
            weakNetwork = WeakNetworkBanner.LOCAL,
        )
        assertEquals(ResUtils.getString(R.string.call_myself_network_poor_tip), afterRecovery?.text)
        assertEquals(CallStatusIcon.WEAK_NETWORK, afterRecovery?.icon)
    }

    @Test
    fun `whole-link recovery outranks the weak-network hint`() {
        val result = resolve(
            CallStatus.CONNECTED,
            callTimerRunning = true,
            mediaSendIssue = MediaSendIssueState.CONNECTION_RECOVERING,
            weakNetwork = WeakNetworkBanner.LOCAL,
        )
        assertEquals(ResUtils.getString(R.string.call_connecting_title), result?.text)
        assertTrue(result!!.spinning)
    }

    @Test
    fun `reconnecting outranks the weak-network hint`() {
        val result = resolve(CallStatus.RECONNECTING, weakNetwork = WeakNetworkBanner.LOCAL)
        assertEquals(ResUtils.getString(R.string.call_connecting_title), result?.text)
    }

    @Test
    fun `the media-ready gate outranks the weak-network hint`() {
        val result = resolve(CallStatus.CONNECTED, callTimerRunning = false, weakNetwork = WeakNetworkBanner.LOCAL)
        assertEquals(ResUtils.getString(R.string.call_connecting_title), result?.text)
    }

    @Test
    fun `one-on-one CALLING keeps the pill empty even with a bad local link`() {
        
        
        assertNull(resolve(CallStatus.CALLING, weakNetwork = WeakNetworkBanner.LOCAL))
    }

    @Test
    fun `a disconnected call shows no weak-network hint`() {
        assertNull(
            resolve(
                CallStatus.DISCONNECTED,
                action = CallIntent.Action.JOIN_CALL,
                weakNetwork = WeakNetworkBanner.LOCAL,
            )
        )
    }
}
