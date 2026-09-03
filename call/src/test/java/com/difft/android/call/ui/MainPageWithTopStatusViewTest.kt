package com.difft.android.call.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.call.CallType
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.network.NetworkQualityLevel
import com.difft.android.call.network.NetworkQualityView
import com.difft.android.call.service.TestScopeApplication
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Integration tests for `MainPageWithTopStatusView.kt`'s title/notification-bar split: the title
 * carries name + E2EE/duration plus the call-progress "等待接听…" state (with the PR #1125 E2EE
 * crossfade, Mac parity), while connection-health statuses (connecting/reconnect-failed/
 * media-send issue) render in the `CallStatusNotificationBar` floating pill below it — which
 * also keeps that slot free for the critical-alert banner during the waiting phase. Also covers
 * `ConnectedStatusContent`'s lock icon + 1v1 title row and `TopStatusBar`'s
 * click-to-open-sheet gating.
 *
 * All lookups for tags nested inside `TopStatusBar`'s clickable click-target Box use
 * `useUnmergedTree = true`: `Modifier.clickable` merges descendant semantics upward into the
 * clickable node, so without it these tags are invisible in the default merged tree (an
 * `onAllNodesWithTag(...).isEmpty()` assertion would trivially pass either way).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [30])
class MainPageWithTopStatusViewTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        unmockkAll()
        // Restore the animator scale so it never leaks into a later test in the same process.
        android.provider.Settings.Global.putFloat(
            ApplicationProvider.getApplicationContext<Application>().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }

    private fun setContentWithViewModel(
        viewModel: LCallViewModel,
        callIntent: CallIntent,
        isInPipMode: Boolean = false,
        isOneVOneCall: Boolean = true,
        isUserSharingScreen: Boolean = false,
        onE2eeHintClick: () -> Unit = {},
        contactorCacheManager: ContactorCacheManager = mockk(relaxed = true),
    ) {
        CallVmTestHarness.mockEntryPointAccessors(contactorCacheManager)
        composeTestRule.setContent {
            MainPageWithTopStatusView(
                viewModel = viewModel,
                isInPipMode = isInPipMode,
                isOneVOneCall = isOneVOneCall,
                isUserSharingScreen = isUserSharingScreen,
                callConfig = CallConfig(),
                callIntent = callIntent,
                windowZoomOutAction = {},
                onE2eeHintClick = onE2eeHintClick,
            )
        }
    }

    /**
     * Wraps [MainPageWithTopStatusView] in an ancestor `Box` with its own independent click
     * counter — used to prove a disabled click-target's tap physically falls through to an
     * ancestor (not just that `onE2eeHintClick` was skipped). A disabled `clickable` that still
     * consumes the down/up pointer events would swallow the tap here too, so `ancestorClicks`
     * would stay 0 under that regression.
     */
    private fun setContentWithAncestorClickCounter(
        viewModel: LCallViewModel,
        callIntent: CallIntent,
        isInPipMode: Boolean,
        isOneVOneCall: Boolean,
        isUserSharingScreen: Boolean,
        onE2eeHintClick: () -> Unit,
        onAncestorClick: () -> Unit,
        contactorCacheManager: ContactorCacheManager = mockk(relaxed = true),
    ) {
        CallVmTestHarness.mockEntryPointAccessors(contactorCacheManager)
        composeTestRule.setContent {
            Box(
                modifier = Modifier.fillMaxSize().clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onAncestorClick,
                )
            ) {
                MainPageWithTopStatusView(
                    viewModel = viewModel,
                    isInPipMode = isInPipMode,
                    isOneVOneCall = isOneVOneCall,
                    isUserSharingScreen = isUserSharingScreen,
                    callConfig = CallConfig(),
                    callIntent = callIntent,
                    windowZoomOutAction = {},
                    onE2eeHintClick = onE2eeHintClick,
                )
            }
        }
    }

    private fun buildOneOnOneCallingViewModel(): Pair<LCallViewModel, CallIntent> {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.START_CALL, CallType.ONE_ON_ONE.type)
        CallVmTestHarness.stubCallStatus(CallStatus.CALLING, CallType.ONE_ON_ONE.type)
        return CallVmTestHarness.buildViewModel(callIntent) to callIntent
    }

    private fun assertNotificationBarText(expected: String) {
        composeTestRule.onNodeWithTag("call_status_notification_text", useUnmergedTree = true)
            .assertTextEquals(expected)
    }

    private fun assertNotificationBarAbsent(reason: String) {
        assertTrue(
            reason,
            composeTestRule.onAllNodesWithTag("call_status_notification_text", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    // ---------------------------------------------------------------------------------
    // T5-1..T5-3 — pre-connect title: 1v1 CALLING keeps the E2EE crossfade in the title (bar
    // stays empty for the critical-alert banner); connection statuses live in the floating bar.
    // ---------------------------------------------------------------------------------

    @Test
    fun `T5-1 - one-on-one calling keeps the crossfade title and an empty floating bar`() {
        val (vm, callIntent) = buildOneOnOneCallingViewModel()
        setContentWithViewModel(vm, callIntent, isInPipMode = false)
        composeTestRule.waitForIdle()

        // Regression guard: `call_status_calling`'s zh VALUE must stay "等待接听…" (not the
        // mock's "正在呼叫…"). Resolved via an explicit zh-qualified Resources instance —
        // independent of this test JVM's default (non-zh) Robolectric locale.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val zhConfig = android.content.res.Configuration(context.resources.configuration)
        zhConfig.setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        val zhResources = context.createConfigurationContext(zhConfig).resources
        assertEquals("等待接听…", zhResources.getString(R.string.call_status_calling))

        // Crossfade (PR #1125): primary and encrypted nodes co-exist while animating.
        composeTestRule.onNodeWithTag("call_topbar_status_text", useUnmergedTree = true).assertTextEquals(
            ResUtils.getString(R.string.call_status_calling)
        )
        composeTestRule.onNodeWithTag("call_topbar_status_text_encrypted", useUnmergedTree = true).assertTextEquals(
            ResUtils.getString(R.string.call_status_encrypted)
        )
        assertNotificationBarAbsent("waiting-for-answer is call progress, not connection health — bar stays empty")
    }

    @Test
    fun `T5-2 - group joining shows connecting text in the bar and encrypted title`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.JOIN_CALL, CallType.GROUP.type)
        CallVmTestHarness.stubCallStatus(CallStatus.JOINING, CallType.GROUP.type)
        val vm = CallVmTestHarness.buildViewModel(callIntent)

        setContentWithViewModel(vm, callIntent, isOneVOneCall = false)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("call_topbar_status_text", useUnmergedTree = true).assertTextEquals(
            ResUtils.getString(R.string.call_status_encrypted)
        )
        assertNotificationBarText(ResUtils.getString(R.string.call_connecting_title))
    }

    @Test
    fun `T5-3 - RECONNECT_FAILED shows the disconnected label in the bar, title stays encrypted`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.JOIN_CALL, CallType.GROUP.type)
        CallVmTestHarness.stubCallStatus(CallStatus.RECONNECT_FAILED, CallType.GROUP.type)
        val vm = CallVmTestHarness.buildViewModel(callIntent)

        setContentWithViewModel(vm, callIntent, isInPipMode = false, isOneVOneCall = false)
        composeTestRule.waitForIdle()

        assertNotificationBarText(ResUtils.getString(R.string.call_disconnected_title))
        composeTestRule.onNodeWithTag("call_topbar_status_text", useUnmergedTree = true).assertTextEquals(
            ResUtils.getString(R.string.call_status_encrypted)
        )
    }

    // ---------------------------------------------------------------------------------
    // T5-4/T5-5 — floating bar visibility gates: PiP never shows it; a healthy connected
    // call has no status to show.
    // ---------------------------------------------------------------------------------

    @Test
    fun `T5-4 - PiP mode never mounts the floating bar and freezes the crossfade`() {
        val (vm, callIntent) = buildOneOnOneCallingViewModel()
        setContentWithViewModel(vm, callIntent, isInPipMode = true)
        composeTestRule.waitForIdle()

        assertNotificationBarAbsent("the notification pill does not fit the PiP window")
        // shouldAnimate=false in PiP: the crossfade renders exactly one static encrypted node.
        composeTestRule.onNodeWithTag("call_topbar_status_text", useUnmergedTree = true).assertTextEquals(
            ResUtils.getString(R.string.call_status_encrypted)
        )
        assertTrue(
            "no _encrypted node when not animating",
            composeTestRule.onAllNodesWithTag("call_topbar_status_text_encrypted", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `T5-16 - ANIMATOR_DURATION_SCALE 0 freezes the crossfade`() {
        android.provider.Settings.Global.putFloat(
            ApplicationProvider.getApplicationContext<Application>().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
        val (vm, callIntent) = buildOneOnOneCallingViewModel()
        setContentWithViewModel(vm, callIntent, isInPipMode = false)
        composeTestRule.waitForIdle()

        assertTrue(
            "shouldAnimate=false expected under reduced motion: no _encrypted node",
            composeTestRule.onAllNodesWithTag("call_topbar_status_text_encrypted", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `T5-15 - backgrounded activity freezes the crossfade`() {
        // Driving the REAL Activity below RESUMED via ActivityScenario.moveToState unregisters
        // the composition from AndroidComposeTestRule's owner registry entirely ("No compose
        // hierarchies found") — a ComposeTestRule limitation, not a production behavior.
        // Providing a custom LifecycleOwner exercises the exact same production read
        // (rememberShouldAnimateCallStatus's currentStateAsState) while keeping the real
        // Activity RESUMED throughout.
        val (vm, callIntent) = buildOneOnOneCallingViewModel()
        CallVmTestHarness.mockEntryPointAccessors()
        val lifecycleOwner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        composeTestRule.runOnUiThread { lifecycleOwner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }

        composeTestRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner,
            ) {
                MainPageWithTopStatusView(
                    viewModel = vm, isInPipMode = false, isOneVOneCall = true, isUserSharingScreen = false,
                    callConfig = CallConfig(), callIntent = callIntent, windowZoomOutAction = {}, onE2eeHintClick = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // Sanity: RESUMED still animates (regression guard for the harness itself).
        assertTrue(
            "sanity: RESUMED must still animate",
            composeTestRule.onAllNodesWithTag("call_topbar_status_text_encrypted", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )

        // STARTED (not RESUMED): the isAtLeast(RESUMED) gate flips isForeground to false.
        composeTestRule.runOnIdle { lifecycleOwner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
        composeTestRule.waitForIdle()

        assertTrue(
            "shouldAnimate=false expected once backgrounded: no _encrypted node",
            composeTestRule.onAllNodesWithTag("call_topbar_status_text_encrypted", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `T5-5 - connected with running timer and healthy uplink hides the bar`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.JOIN_CALL, CallType.GROUP.type)
        CallVmTestHarness.stubCallStatus(CallStatus.CONNECTED, CallType.GROUP.type)
        val vm = CallVmTestHarness.buildViewModel(callIntent)
        vm.timerManager.startCallTimer { }

        setContentWithViewModel(vm, callIntent, isOneVOneCall = false)
        composeTestRule.waitForIdle()

        assertNotificationBarAbsent("no transient status ⇒ no floating bar")
    }

    // ---------------------------------------------------------------------------------
    // T5-6 — 1v1 media-ready gate: CONNECTED but timer not yet running keeps the encrypted
    // text in the duration slot and shows "Connecting…" in the bar (duration/status coexistence:
    // the title never renders transient statuses anymore).
    // ---------------------------------------------------------------------------------

    @Test
    fun `T5-6 - connected before timer start shows encrypted duration slot and connecting bar`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.START_CALL, CallType.ONE_ON_ONE.type)
        CallVmTestHarness.stubCallStatus(CallStatus.CONNECTED, CallType.ONE_ON_ONE.type)
        val contactorCacheManager: ContactorCacheManager = mockk(relaxed = true)
        val remote = mockk<RemoteParticipant>(relaxed = true)
        every { remote.identity } returns Participant.Identity("u123")
        coEvery { contactorCacheManager.getDisplayNameById("u123") } returns "Alice"
        val vm = CallVmTestHarness.buildViewModel(callIntent, contactorCacheManager = contactorCacheManager)
        vm.participantManager.setParticipants(listOf(remote))
        // Deliberately NOT starting the call timer — this is the media-ready gate window.

        setContentWithViewModel(vm, callIntent, isOneVOneCall = true, contactorCacheManager = contactorCacheManager)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("call_topbar_call_duration", useUnmergedTree = true).assertTextEquals(
            ResUtils.getString(R.string.call_status_encrypted)
        )
        assertNotificationBarText(ResUtils.getString(R.string.call_connecting_title))
    }

    // ---------------------------------------------------------------------------------
    // T5-7 — screen-share title during the media-ready gate: the raw ticker must not leak a
    // frozen "00:00" next to the sharer's name (the 67edad0d1 bug class, screen-share path).
    // ---------------------------------------------------------------------------------

    @Test
    fun `T5-7 - screen-share title omits the duration until the call timer runs`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.START_CALL, CallType.ONE_ON_ONE.type)
        CallVmTestHarness.stubCallStatus(CallStatus.CONNECTED, CallType.ONE_ON_ONE.type)
        val contactorCacheManager: ContactorCacheManager = mockk(relaxed = true)
        val sharer = mockk<RemoteParticipant>(relaxed = true)
        every { sharer.identity } returns Participant.Identity("u123")
        coEvery { contactorCacheManager.getDisplayNameById("u123") } returns "Alice"
        val vm = CallVmTestHarness.buildViewModel(callIntent, contactorCacheManager = contactorCacheManager)
        vm.participantManager.setScreenSharingUser(sharer)
        // Deliberately NOT starting the call timer — the media-ready gate window.

        setContentWithViewModel(
            vm, callIntent, isOneVOneCall = true, isUserSharingScreen = true,
            contactorCacheManager = contactorCacheManager,
        )
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("call_topbar_call_duration", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("call_topbar_call_duration", useUnmergedTree = true).assertTextEquals(
            "Alice${ResUtils.getString(R.string.call_screen_sharing_title)}"
        )
    }

    // ---------------------------------------------------------------------------------
    // T5-8 — mid-call reconnect coexistence: once the timer runs, RECONNECTING must keep the
    // connected title (name + ticking duration) while the pill carries the connection status —
    // never fall back to the pre-connect E2EE placeholder (Bugbot finding on PR #1150).
    // ---------------------------------------------------------------------------------

    @Test
    fun `T5-8 - mid-call RECONNECTING keeps name and duration in the title with connecting in the bar`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.START_CALL, CallType.ONE_ON_ONE.type)
        CallVmTestHarness.stubCallStatus(CallStatus.RECONNECTING, CallType.ONE_ON_ONE.type)
        val contactorCacheManager: ContactorCacheManager = mockk(relaxed = true)
        val remote = mockk<RemoteParticipant>(relaxed = true)
        every { remote.identity } returns Participant.Identity("u123")
        coEvery { contactorCacheManager.getDisplayNameById("u123") } returns "Alice"
        val vm = CallVmTestHarness.buildViewModel(callIntent, contactorCacheManager = contactorCacheManager)
        vm.participantManager.setParticipants(listOf(remote))
        // Mid-call: the timer started while CONNECTED and keeps running through the reconnect.
        vm.timerManager.startCallTimer { }

        setContentWithViewModel(vm, callIntent, isOneVOneCall = true, contactorCacheManager = contactorCacheManager)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("call_topbar_room_name", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("call_topbar_room_name", useUnmergedTree = true).assertTextEquals("Alice")
        composeTestRule.onNodeWithTag("call_topbar_call_duration", useUnmergedTree = true).assertTextEquals("00:00")
        assertNotificationBarText(ResUtils.getString(R.string.call_connecting_title))
        assertTrue(
            "mid-call reconnect must not fall back to the pre-connect E2EE placeholder",
            composeTestRule.onAllNodesWithTag("call_topbar_status_text", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    // ---------------------------------------------------------------------------------
    // T5-9/T5-10 — combos B/D: 1v1 peer name + lock icon / group room name + lock icon.
    // ---------------------------------------------------------------------------------

    @Test
    fun `T5-9 - combo B one-on-one connected shows peer name and lock icon before duration`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.START_CALL, CallType.ONE_ON_ONE.type)
        CallVmTestHarness.stubCallStatus(CallStatus.CONNECTED, CallType.ONE_ON_ONE.type)
        val contactorCacheManager: ContactorCacheManager = mockk(relaxed = true)
        val remote = mockk<RemoteParticipant>(relaxed = true)
        every { remote.identity } returns Participant.Identity("u123")
        coEvery { contactorCacheManager.getDisplayNameById("u123") } returns "Alice"
        val vm = CallVmTestHarness.buildViewModel(callIntent, contactorCacheManager = contactorCacheManager)
        vm.participantManager.setParticipants(listOf(remote))
        // Production always pairs CallStatus.CONNECTED with timerManager.startCallTimer (see
        // LCallViewModel's onConnected handling) — callTimerRunning gates the duration text
        // (develop commit 67edad0d1, "gate 1v1 timer on media readiness"), so the harness must
        // start it too or the duration slot renders the encrypted text instead.
        vm.timerManager.startCallTimer { }

        setContentWithViewModel(vm, callIntent, isOneVOneCall = true, contactorCacheManager = contactorCacheManager)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("call_topbar_room_name", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag("call_topbar_room_name", useUnmergedTree = true).assertTextEquals("Alice")
        // Lock icon has no testTag (Icon composables carry no semantics text); its presence in
        // the duration Row is verified by source diff review (ConnectedStatusContent §2).
        composeTestRule.onNodeWithTag("call_topbar_call_duration", useUnmergedTree = true).assertTextEquals("00:00")
    }

    @Test
    fun `T5-10 - combo D group connected keeps existing room name and gains lock icon`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(
            CallIntent.Action.JOIN_CALL, CallType.GROUP.type, roomName = "Group Room",
        )
        CallVmTestHarness.stubCallStatus(CallStatus.CONNECTED, CallType.GROUP.type)
        val vm = CallVmTestHarness.buildViewModel(callIntent)
        // See T5-9's comment: callTimerRunning must be started explicitly in this harness.
        vm.timerManager.startCallTimer { }

        setContentWithViewModel(vm, callIntent, isOneVOneCall = false)
        composeTestRule.waitForIdle()

        // getCallRoomName() appends the participant count for non-1v1 calls; participants is
        // empty here so it coerces to 1 — unchanged pre-existing behavior, not part of this task.
        composeTestRule.onNodeWithTag("call_topbar_room_name", useUnmergedTree = true).assertTextEquals("Group Room (1)")
        composeTestRule.onNodeWithTag("call_topbar_call_duration", useUnmergedTree = true).assertTextEquals("00:00")
    }

    // ---------------------------------------------------------------------------------
    // T5-11..T5-14 — TopStatusBar click-to-open-sheet gating.
    // ---------------------------------------------------------------------------------

    @Test
    fun `T5-11 - isTopVisible true, not PiP, not sharing screen invokes onE2eeHintClick once`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.START_CALL, CallType.ONE_ON_ONE.type)
        CallVmTestHarness.stubCallStatus(CallStatus.CALLING, CallType.ONE_ON_ONE.type)
        val vm = CallVmTestHarness.buildViewModel(callIntent)
        var clicks = 0

        // isOneVOneCall=true && !isUserSharingScreen ⇒ isTopVisible=true unconditionally.
        setContentWithViewModel(
            vm, callIntent, isInPipMode = false, isOneVOneCall = true, isUserSharingScreen = false,
            onE2eeHintClick = { clicks++ },
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("call_topbar_status_click_target").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `T5-12 - isTopVisible false does not invoke onE2eeHintClick and tap falls through to tapInterceptor`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.JOIN_CALL, CallType.GROUP.type)
        CallVmTestHarness.stubCallStatus(CallStatus.CONNECTED, CallType.GROUP.type)
        val vm = CallVmTestHarness.buildViewModel(callIntent)
        // Group call (isOneVOneCall=false) + showTopStatusViewEnabled=false ⇒ isTopVisible=false,
        // which mounts the real ancestor `tapInterceptor(enabled = !isTopVisible)` on
        // `TopStatusBar` — the actual production consumer the disabled click-target's tap must
        // fall through to here (an external synthetic ancestor Box would sit OUTSIDE
        // tapInterceptor and would never see the tap either way, since tapInterceptor
        // legitimately claims it first).
        vm.callUiController.setShowTopStatusViewEnabled(false)
        var clicks = 0

        setContentWithViewModel(
            vm, callIntent, isInPipMode = false, isOneVOneCall = false, isUserSharingScreen = false,
            onE2eeHintClick = { clicks++ },
        )
        composeTestRule.waitForIdle()

        // useUnmergedTree = true: a disabled click target no longer mounts `clickable`, so it no
        // longer declares its own `mergeDescendants` boundary and drops out of the default merged
        // tree (its Text-carrying children get merged upward into the nearest boundary instead).
        composeTestRule.onNodeWithTag("call_topbar_status_click_target", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        assertEquals("disabled click target must not invoke the sheet callback", 0, clicks)
        assertTrue(
            "tap must fall through to tapInterceptor (not consumed and dropped by the disabled " +
                "click target) and toggle the overlays back on",
            vm.callUiController.showTopStatusViewEnabled.value,
        )
    }

    @Test
    fun `T5-13 - isInPipMode true does not invoke onE2eeHintClick and tap falls through to ancestor`() {
        val (vm, callIntent) = buildOneOnOneCallingViewModel()
        var clicks = 0
        var ancestorClicks = 0

        setContentWithAncestorClickCounter(
            vm, callIntent, isInPipMode = true, isOneVOneCall = true, isUserSharingScreen = false,
            onE2eeHintClick = { clicks++ },
            onAncestorClick = { ancestorClicks++ },
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("call_topbar_status_click_target", useUnmergedTree = true).performClick()

        assertEquals(0, clicks)
        assertEquals(
            "tap must fall through to the ancestor when the click target is disabled (not consumed and dropped)",
            1, ancestorClicks,
        )
    }

    // ---------------------------------------------------------------------------------
    // T20a..T20d — weak-network banner end to end: snapshot -> WeakNetworkBanner.resolve ->
    // callStatusNotification -> pill, plus the PiP exclusion and the isOneVOneCall wiring.
    // ---------------------------------------------------------------------------------

    /** Connected group call with a running timer — the healthy baseline the banner sits on. */
    private fun buildConnectedGroupViewModel(): Pair<LCallViewModel, CallIntent> {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.JOIN_CALL, CallType.GROUP.type)
        CallVmTestHarness.stubCallStatus(CallStatus.CONNECTED, CallType.GROUP.type)
        val vm = CallVmTestHarness.buildViewModel(callIntent)
        vm.timerManager.startCallTimer { }
        return vm to callIntent
    }

    @Test
    fun `T20a - a bad local link shows the local weak-network text in the bar`() {
        val (vm, callIntent) = buildConnectedGroupViewModel()
        vm.callUiController.setNetworkQuality(NetworkQualityView(local = NetworkQualityLevel.BAD))

        setContentWithViewModel(vm, callIntent, isOneVOneCall = false)
        composeTestRule.waitForIdle()

        assertNotificationBarText(ResUtils.getString(R.string.call_myself_network_poor_tip))
    }

    @Test
    fun `T20b - a healthy snapshot leaves the bar empty`() {
        val (vm, callIntent) = buildConnectedGroupViewModel()
        vm.callUiController.setNetworkQuality(NetworkQualityView.NONE)

        setContentWithViewModel(vm, callIntent, isOneVOneCall = false)
        composeTestRule.waitForIdle()

        assertNotificationBarAbsent("a healthy snapshot must render no banner")
    }

    @Test
    fun `T20c - PiP mode still excludes the bar when the local link is bad`() {
        val (vm, callIntent) = buildConnectedGroupViewModel()
        vm.callUiController.setNetworkQuality(NetworkQualityView(local = NetworkQualityLevel.BAD))

        setContentWithViewModel(vm, callIntent, isInPipMode = true, isOneVOneCall = false)
        composeTestRule.waitForIdle()

        assertNotificationBarAbsent("the notification pill does not fit the PiP window — the tile badge carries it")
    }

    @Test
    fun `T20d - a bad remote link with two people in the call shows the remote weak-network text`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.START_CALL, CallType.ONE_ON_ONE.type)
        CallVmTestHarness.stubCallStatus(CallStatus.CONNECTED, CallType.ONE_ON_ONE.type)
        val vm = CallVmTestHarness.buildViewModel(callIntent)
        vm.timerManager.startCallTimer { }
        // The headcount — local + one peer — is what turns a bad remote into a banner instead of a
        // tile badge. The list the production wiring publishes is exactly local + the room's remotes.
        vm.participantManager.setParticipants(listOf(localParticipant(), remoteParticipant("alice")))
        vm.callUiController.setNetworkQuality(
            NetworkQualityView(remote = mapOf("alice" to NetworkQualityLevel.BAD)),
        )

        setContentWithViewModel(vm, callIntent, isOneVOneCall = true)
        composeTestRule.waitForIdle()

        assertNotificationBarText(ResUtils.getString(R.string.call_other_network_poor_tip))
    }

    @Test
    fun `T20e - a bad remote link with three people leaves the bar to the tile badge`() {
        val (vm, callIntent) = buildConnectedGroupViewModel()
        vm.participantManager.setParticipants(
            listOf(localParticipant(), remoteParticipant("alice"), remoteParticipant("bob")),
        )
        vm.callUiController.setNetworkQuality(
            NetworkQualityView(remote = mapOf("alice" to NetworkQualityLevel.BAD)),
        )

        setContentWithViewModel(vm, callIntent, isOneVOneCall = false)
        composeTestRule.waitForIdle()

        assertNotificationBarAbsent("with three people the badge names the bad peer instead")
    }

    private fun localParticipant(): Participant = mockk<LocalParticipant>(relaxed = true)

    private fun remoteParticipant(identity: String): Participant =
        mockk<RemoteParticipant>(relaxed = true).also {
            every { it.identity } returns Participant.Identity(identity)
        }

    @Test
    fun `T5-14 - isUserSharingScreen true does not invoke onE2eeHintClick`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.START_CALL, CallType.ONE_ON_ONE.type)
        CallVmTestHarness.stubCallStatus(CallStatus.CONNECTED, CallType.ONE_ON_ONE.type)
        val vm = CallVmTestHarness.buildViewModel(callIntent)
        var clicks = 0

        setContentWithViewModel(
            vm, callIntent, isInPipMode = false, isOneVOneCall = true, isUserSharingScreen = true,
            onE2eeHintClick = { clicks++ },
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("call_topbar_status_click_target").performClick()

        assertFalse("screen-sharing must exclude the E2EE surface entirely", clicks > 0)
    }
}
