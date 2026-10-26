package com.difft.android.call.manager

import android.app.Application
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.utils.NotificationRepostGuard
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.call.state.CriticalAlertRoomRegistry
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.util.FlashLightBlinker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [CriticalAlertManager] room dismissal and page ownership (#1387), and the IndexActivity rule
 * (#213 / #1387): a call end stops only the alerts recorded for its room, the page only when it
 * shows one of them, and sound and flashlight only for the alert that owns the play token; a page
 * released for an ended room leaves another alert's playback alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CriticalAlertManagerDismissTest {

    private val application: Application = RuntimeEnvironment.getApplication()
    private val stateManager = CriticalAlertStateManager()
    private val registry = CriticalAlertRoomRegistry()
    private val gate = CriticalAlertCallGate(OnGoingCallStateManager(), registry)
    private val soundPlayer = mockk<CriticalAlertSoundPlayer>(relaxed = true)
    private val repostGuard = mockk<NotificationRepostGuard>(relaxed = true)
    private lateinit var manager: CriticalAlertManager

    @Before
    fun setUp() {
        mockkObject(FlashLightBlinker)
        every { FlashLightBlinker.stopBlinking() } returns Unit
        every { soundPlayer.stopIfOwner(any()) } returns false
        manager = CriticalAlertManager(
            context = application,
            criticalAlertStateManager = stateManager,
            activityProvider = mockk(relaxed = true),
            inComingCallStateManager = mockk(relaxed = true),
            userManager = mockk(relaxed = true),
            criticalAlertSoundPlayer = soundPlayer,
            criticalAlertCallGate = gate,
            criticalAlertRoomRegistry = registry,
            repostGuard = repostGuard,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `dismissing a room cancels only its notifications and stops only the alert that owns the sound`() {
        manager.recordRoomAlert(ROOM_A, ID_A1)
        manager.recordRoomAlert(ROOM_A, ID_A2)
        manager.recordRoomAlert(ROOM_B, ID_B)
        every { soundPlayer.stopIfOwner(ID_A2) } returns true

        manager.dismissForRoom(ROOM_A)

        verify(exactly = 1) { repostGuard.cancel(ID_A1) }
        verify(exactly = 1) { repostGuard.cancel(ID_A2) }
        verify(exactly = 0) { repostGuard.cancel(ID_B) }
        verify(exactly = 1) { soundPlayer.stopIfOwner(ID_A1) }
        verify(exactly = 1) { soundPlayer.stopIfOwner(ID_A2) }
        verify(exactly = 0) { soundPlayer.stopIfOwner(ID_B) }
        // A silenced alert's blinking ends itself; another alert's flashlight is never cut here.
        verify(exactly = 0) { FlashLightBlinker.stopBlinking() }
        assertTrue(registry.isEnded(ROOM_A))
        assertFalse(registry.isEnded(ROOM_B))
    }

    @Test
    fun `dismissing a room whose alert owns nothing keeps another alert's flashlight`() {
        manager.recordRoomAlert(ROOM_A, ID_A1)
        // Another alert's sound owns the token: stopIfOwner(ID_A1) is false.

        manager.dismissForRoom(ROOM_A)

        verify(exactly = 0) { FlashLightBlinker.stopBlinking() }
    }

    @Test
    fun `dismissing an unknown room only marks it ended`() {
        manager.dismissForRoom(ROOM_A)

        verify(exactly = 0) { repostGuard.cancel(any()) }
        verify(exactly = 0) { soundPlayer.stopIfOwner(any()) }
        assertTrue(shadowOf(application).broadcastIntents.isEmpty())
        assertTrue("a late alert for it is silenced", gate.shouldSilence(CONVERSATION, ROOM_A))
    }

    @Test
    fun `an empty room id is ignored`() {
        manager.dismissForRoom("")

        assertFalse(registry.isEnded(""))
    }

    @Test
    fun `the page closes only when it shows one of the room's alerts`() {
        manager.recordRoomAlert(ROOM_A, ID_A1)
        manager.recordRoomAlert(ROOM_B, ID_B)
        manager.publishPageAndCheckSilence(ID_B, CONVERSATION, ROOM_B)

        manager.dismissForRoom(ROOM_A)

        assertTrue("page shows room B's alert", shadowOf(application).broadcastIntents.isEmpty())

        manager.dismissForRoom(ROOM_B)

        val sent = shadowOf(application).broadcastIntents.single()
        assertEquals(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS_BY_NOTIFICATION_ID, sent.action)
        assertEquals(application.packageName, sent.`package`)
        assertEquals(ID_B, sent.getIntExtra(LCallConstants.CRITICAL_ALERT_PARAM_NOTIFICATION_ID, -1))
    }

    @Test
    fun `the page closes after the sound auto-stopped`() {
        manager.recordRoomAlert(ROOM_A, ID_A1)
        manager.publishPageAndCheckSilence(ID_A1, CONVERSATION, ROOM_A)
        // The 30 s auto-stop clears the sound state; the page id is independent of it.
        stateManager.resetSoundState()

        manager.dismissForRoom(ROOM_A)

        assertEquals(1, shadowOf(application).broadcastIntents.size)
    }

    @Test
    fun `a failure while dismissing is logged and not thrown to the call handler`() {
        manager.recordRoomAlert(ROOM_A, ID_A1)
        every { repostGuard.cancel(any()) } throws IllegalStateException("boom")

        manager.dismissForRoom(ROOM_A)

        assertTrue(registry.isEnded(ROOM_A))
    }

    @Test
    fun `a page that publishes after the room ended is told to close and its id is visible`() {
        registry.end(ROOM_A)

        val silenced = manager.publishPageAndCheckSilence(ID_A1, CONVERSATION, ROOM_A)

        assertTrue(silenced)
        assertEquals(ID_A1, stateManager.getPageNotificationId())
    }

    @Test
    fun `a page of a live room is not silenced`() {
        assertFalse(manager.publishPageAndCheckSilence(ID_A1, CONVERSATION, ROOM_A))
        assertEquals(ID_A1, stateManager.getPageNotificationId())
    }

    @Test
    fun `a page finished for an ended room leaves another alert's sound and flashlight running`() {
        manager.publishPageAndCheckSilence(ID_A1, CONVERSATION, ROOM_A)
        stateManager.setIsShowing(true)
        stateManager.setConversationId(CONVERSATION)
        // Room B's alert owns the play token, so the page's alert (room A) does not.
        every { soundPlayer.stopIfOwner(ID_A1) } returns false

        manager.releasePage(ID_A1, CONVERSATION, stopOwnedAlert = true)

        verify(exactly = 1) { soundPlayer.stopIfOwner(ID_A1) }
        verify(exactly = 0) { soundPlayer.stopSound() }
        verify(exactly = 0) { FlashLightBlinker.stopBlinking() }
        assertNull(stateManager.getPageNotificationId())
        assertFalse(stateManager.isShowing())
        assertNull(stateManager.getConversationId())
    }

    @Test
    fun `a page finished for an ended room stops the sound it owns`() {
        manager.publishPageAndCheckSilence(ID_A1, CONVERSATION, ROOM_A)
        every { soundPlayer.stopIfOwner(ID_A1) } returns true

        manager.releasePage(ID_A1, CONVERSATION, stopOwnedAlert = true)

        verify(exactly = 1) { soundPlayer.stopIfOwner(ID_A1) }
        verify(exactly = 0) { FlashLightBlinker.stopBlinking() }
    }

    @Test
    fun `closing a page keeps the conversation of another alert whose sound is current`() {
        manager.publishPageAndCheckSilence(ID_A1, CONVERSATION, ROOM_A)
        stateManager.setIsShowing(true)
        stateManager.setConversationId(CONVERSATION)
        // Alert B of the same conversation, another room, rings in the background.
        stateManager.setCurrentNotificationId(ID_B)

        manager.releasePage(ID_A1, CONVERSATION, stopOwnedAlert = true)

        assertEquals(CONVERSATION, stateManager.getConversationId())
    }

    @Test
    fun `a page that never showed releases its id without stopping anything`() {
        manager.publishPageAndCheckSilence(ID_A1, CONVERSATION, ROOM_A)

        manager.releasePage(ID_A1, CONVERSATION, stopOwnedAlert = false)

        verify(exactly = 0) { soundPlayer.stopIfOwner(any()) }
        assertNull(stateManager.getPageNotificationId())
    }

    @Test
    fun `a page replaced by a newer alert does not clear the newer alert's state`() {
        manager.publishPageAndCheckSilence(ID_A1, CONVERSATION, ROOM_A)
        // The page now shows alert B, which published its id and state.
        manager.publishPageAndCheckSilence(ID_B, OTHER_CONVERSATION, ROOM_B)
        stateManager.setIsShowing(true)
        stateManager.setConversationId(OTHER_CONVERSATION)

        manager.releasePage(ID_A1, CONVERSATION, stopOwnedAlert = false)

        assertEquals(ID_B, stateManager.getPageNotificationId())
        assertTrue(stateManager.isShowing())
        assertEquals(OTHER_CONVERSATION, stateManager.getConversationId())
    }

    @Test
    fun `stopIfOwner never cuts a flashlight`() {
        every { soundPlayer.stopIfOwner(ID_A1) } returns true

        manager.stopIfOwner(ID_A1)

        verify(exactly = 1) { soundPlayer.stopIfOwner(ID_A1) }
        verify(exactly = 0) { FlashLightBlinker.stopBlinking() }
    }

    @Test
    fun `an alert ringing without its page is dismissed when IndexActivity starts`() {
        stateManager.setIsPlayingSound(true)

        assertTrue(manager.shouldDismissWhenIndexStarts())
    }

    @Test
    fun `a shown alert without a visible page is dismissed when IndexActivity starts`() {
        stateManager.setIsShowing(true)

        assertTrue(manager.shouldDismissWhenIndexStarts())
    }

    @Test
    fun `IndexActivity starting beneath the visible alert page does not dismiss it`() {
        stateManager.setIsShowing(true)
        stateManager.setIsPlayingSound(true)
        stateManager.setPageVisible(true)

        assertFalse(manager.shouldDismissWhenIndexStarts())

        stateManager.setPageVisible(false)

        assertTrue("the user left the page", manager.shouldDismissWhenIndexStarts())
    }

    @Test
    fun `IndexActivity uncovered by a page closing for its call does not dismiss another alert`() {
        manager.publishPageAndCheckSilence(ID_A1, CONVERSATION, ROOM_A)
        stateManager.setIsShowing(true)
        stateManager.setIsPlayingSound(true)
        // The page closes for its ended call; it has paused, but onDestroy has not run yet.
        stateManager.setPageClosingSilently(ID_A1)

        assertFalse(manager.shouldDismissWhenIndexStarts())

        manager.releasePage(ID_A1, CONVERSATION, stopOwnedAlert = true)

        assertTrue("released: another alert ringing is dismissed again", manager.shouldDismissWhenIndexStarts())
    }

    @Test
    fun `a newer page does not leave the closing mark of the page it followed stuck`() {
        manager.publishPageAndCheckSilence(ID_A1, CONVERSATION, ROOM_A)
        stateManager.setPageClosingSilently(ID_A1)
        // A new page instance publishes before the closing page's onDestroy runs.
        manager.publishPageAndCheckSilence(ID_B, OTHER_CONVERSATION, ROOM_B)
        stateManager.setIsShowing(true)
        stateManager.setIsPlayingSound(true)

        assertTrue("the closing mark belongs to the old page only", manager.shouldDismissWhenIndexStarts())

        manager.releasePage(ID_A1, CONVERSATION, stopOwnedAlert = true)

        assertNull(stateManager.getStateSnapshot().pageClosingSilentlyId)
        assertEquals(ID_B, stateManager.getPageNotificationId())
    }

    @Test
    fun `a joining alert is not dismissed when IndexActivity starts`() {
        stateManager.setIsShowing(true)
        stateManager.setIsJoining(true)

        assertFalse(manager.shouldDismissWhenIndexStarts())
    }

    @Test
    fun `nothing running means nothing to dismiss when IndexActivity starts`() {
        assertFalse(manager.shouldDismissWhenIndexStarts())
    }

    @Test
    fun `a reset clears the page's id and visibility`() {
        stateManager.setPageNotificationId(ID_A1)
        stateManager.setPageVisible(true)

        stateManager.reset()

        assertNull(stateManager.getPageNotificationId())
        assertFalse(stateManager.isPageVisible())
    }

    private companion object {
        const val CONVERSATION = "peer-1"
        const val OTHER_CONVERSATION = "peer-2"
        const val ROOM_A = "room-a"
        const val ROOM_B = "room-b"
        const val ID_A1 = 11
        const val ID_A2 = 12
        const val ID_B = 22
    }
}
