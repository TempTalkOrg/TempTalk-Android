package com.difft.android.call.manager

import android.media.Ringtone
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.call.state.CriticalAlertRoomRegistry
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [CriticalAlertSoundPlayer.playSound] for an alert of the call being joined or already active
 * (#1381): refused before queuing, and a queued play that finds the alert matching once it runs
 * leaves every playback state, including another alert's ringtone, untouched.
 */
class CriticalAlertSoundPlayerCurrentCallTest {

    private val stateManager = mockk<CriticalAlertStateManager>(relaxed = true)
    private val vibrationManager = mockk<CallVibrationManager>(relaxed = true)

    @Test
    fun `an alert for the joining call is refused without touching playback state`() {
        val gate = CriticalAlertCallGate(OnGoingCallStateManager(), CriticalAlertRoomRegistry())
        val player = CriticalAlertSoundPlayer(mockk(relaxed = true), stateManager, vibrationManager, gate)
        gate.beginJoin(ROOM, CONVERSATION)
        var admitted = 0

        player.playSound(CONVERSATION, NOTIFICATION_ID, ROOM) { admitted++ }
        // No room: matched on the conversation.
        player.playSound(CONVERSATION, NOTIFICATION_ID) { admitted++ }

        assertEquals(0, admitted)
        confirmVerified(stateManager, vibrationManager)
    }

    @Test
    fun `a queued play that matches once it runs keeps another alert's ringtone`() {
        // Not matched before queuing, matched when the queued play runs.
        val gate = mockk<CriticalAlertCallGate>()
        every { gate.shouldSilence(CONVERSATION, ROOM) } returnsMany listOf(false, true)
        val player = CriticalAlertSoundPlayer(mockk(relaxed = true), stateManager, vibrationManager, gate)
        val otherRingtone = mockk<Ringtone>(relaxed = true)
        setCurrentRingtone(player, otherRingtone)
        every { stateManager.getCurrentNotificationId() } returns OTHER_NOTIFICATION_ID

        val admitted = AtomicBoolean(false)

        player.playSound(CONVERSATION, NOTIFICATION_ID, ROOM) { admitted.set(true) }
        verify(timeout = 5_000, exactly = 2) { gate.shouldSilence(CONVERSATION, ROOM) }
        // Takes soundLock, so the queued play's locked section has finished.
        player.stopSoundIfMatch(UNRELATED_NOTIFICATION_ID)

        assertFalse("the flashlight follows the sound's admission", admitted.get())
        assertSame(otherRingtone, currentRingtone(player))
        verify(exactly = 0) { stateManager.setCurrentPlayToken(any()) }
        verify(exactly = 0) { otherRingtone.stop() }
    }

    private fun ringtoneField() =
        CriticalAlertSoundPlayer::class.java.getDeclaredField("currentRingtone").apply { isAccessible = true }

    private fun setCurrentRingtone(player: CriticalAlertSoundPlayer, ringtone: Ringtone) = ringtoneField().set(player, ringtone)

    private fun currentRingtone(player: CriticalAlertSoundPlayer): Any? = ringtoneField().get(player)

    private companion object {
        const val ROOM = "room-1"
        const val CONVERSATION = "peer-1"
        const val NOTIFICATION_ID = 42
        const val OTHER_NOTIFICATION_ID = 7
        const val UNRELATED_NOTIFICATION_ID = 99
    }
}
