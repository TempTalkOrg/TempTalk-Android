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

/**
 * Full decision matrix for [callStatusNotification] — the single resolver behind the floating
 * status pill. Priority contract under test: connecting/reconnecting states always outrank the
 * media-send issue (doc rule), and a healthy connected call shows nothing.
 */
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
        // Defaulted so every pre-existing case below keeps exercising the untouched decision path:
        // with NONE the resolver must behave exactly as it did before the weak-network slot existed.
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
        // Call progress, not connection health: the title renders "等待接听…" with the E2EE
        // crossfade (Mac parity), and the pill slot stays free for the critical-alert banner.
        assertNull(resolve(CallStatus.CALLING))
    }

    @Test
    fun `one-on-one callee in CALLING also keeps the pill empty`() {
        // Without the explicit CALLING guard, a callee's JOIN intent would satisfy the
        // loading clause and double-render as "Connecting…" next to the title's crossfade.
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
        // Sustained mid-call unhealthy state — must surface like RECONNECTING; the legacy title
        // gate omitted it, leaving a 1v1 caller with no feedback during the switch.
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
        // Pre-existing shouldShowLoadingStatus behavior, deliberately preserved: a 1v1 caller's
        // START_CALL intent satisfies none of the loading clauses.
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
        // Doc acceptance: RECOVERING and FAILED both map to SEND_RECOVERING and share this one
        // presentation (spinner kept) — no distinct no-recovery failure state exists anymore.
        val result = resolve(
            CallStatus.CONNECTED, callTimerRunning = true, mediaSendIssue = MediaSendIssueState.SEND_RECOVERING,
        )
        assertEquals(ResUtils.getString(R.string.call_media_send_issue), result?.text)
        assertTrue(result!!.spinning)
    }

    @Test
    fun `connected with whole-link recovery shows the connecting label`() {
        // Doc acceptance (network loss): SDK ROOM_RECOVERING arrives while the room status flow
        // still says CONNECTED — the pill must show the connection presentation, never the
        // media-send hint.
        val result = resolve(
            CallStatus.CONNECTED, callTimerRunning = true, mediaSendIssue = MediaSendIssueState.CONNECTION_RECOVERING,
        )
        assertEquals(ResUtils.getString(R.string.call_connecting_title), result?.text)
        assertTrue(result!!.spinning)
    }

    @Test
    fun `media-ready gate outranks the media send issue`() {
        // Doc rule: any connecting state beats "send issue" — while the timer hasn't started,
        // the pill says connecting even if the uplink already reports a problem.
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

    // ---------------------------------------------------------------------------------
    // Weak network — the pill's two new statuses and their place in the priority chain.
    // ---------------------------------------------------------------------------------

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
        // A real uplink failure the peer already hears beats a hint that quality "may" be
        // affected — and a weak local link is often this state's own cause.
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
        // Arbitration case 1, peer side: the uplink tier wins over either weak-network tier, so the
        // pill can never be talked out of reporting a failure the peer already hears.
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
        // Arbitration case 2. The uplink tier merely OUTRANKS the weak-network tier; it does not
        // gate, delay or reset it. So the frame in which the uplink issue clears — with the link
        // still weak — must already carry the weak-network copy, with no second 3 s wait. The
        // verdict behind it is kept alive throughout because an uplink issue is not a suppression
        // input (see `an uplink-only issue does not suppress the verdict`).
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
        // The waiting-for-answer early return wins: media isn't established yet and the slot is
        // reserved for the critical-alert banner during that phase.
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
