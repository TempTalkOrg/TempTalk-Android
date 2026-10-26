package com.difft.android.call.manager

import android.media.Ringtone
import android.media.RingtoneManager
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.call.state.CriticalAlertRoomRegistry
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
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
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [CriticalAlertSoundPlayer] when the call of an alert ends (#1387): an end landing between the
 * first lock block and the ringtone install does not start the ringtone, and [stopIfOwner] stops
 * only the alert that owns the play token, pending or playing, never another alert's playback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CriticalAlertSoundPlayerRoomEndTest {

    private val stateManager = CriticalAlertStateManager()
    private val registry = CriticalAlertRoomRegistry()
    private val gate = CriticalAlertCallGate(OnGoingCallStateManager(), registry)
    private lateinit var player: CriticalAlertSoundPlayer

    @Before
    fun setUp() {
        mockkStatic(RingtoneManager::class)
        player = CriticalAlertSoundPlayer(
            RuntimeEnvironment.getApplication(),
            stateManager,
            mockk(relaxed = true),
            gate,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a room that ends after the first lock block does not start the ringtone`() {
        val ringtone = mockk<Ringtone>(relaxed = true)
        // The end lands while the ringtone is being prepared, between Phase 1 and Phase 3.
        every { RingtoneManager.getRingtone(any(), any()) } answers {
            registry.end(ROOM_A)
            ringtone
        }

        player.playSound(CONVERSATION, ID_A, ROOM_A)

        verify(timeout = WAIT_MS) { ringtone.stop() }
        assertFalse(stateManager.isPlayingSound())
        assertNull(stateManager.getCurrentNotificationId())
        assertEquals("the token this play took is released", 0L, stateManager.getCurrentPlayToken())
        verify(exactly = 0) { ringtone.play() }
        assertFalse("nothing is owned any more", player.stopIfOwner(ID_A))
    }

    @Test
    fun `a silenced newer alert clears the playing state of the alert it replaced`() {
        val ringtoneA = mockk<Ringtone>(relaxed = true)
        val ringtoneB = mockk<Ringtone>(relaxed = true)
        every { RingtoneManager.getRingtone(any(), any()) } returns ringtoneA
        player.playSound(CONVERSATION, ID_A, ROOM_A)
        waitUntil { stateManager.getCurrentNotificationId() == ID_A }
        // B's Phase 1 stops A's ringtone; B's room ends while B's ringtone is being prepared.
        every { RingtoneManager.getRingtone(any(), any()) } answers {
            registry.end(ROOM_B)
            ringtoneB
        }

        player.playSound(CONVERSATION, ID_B, ROOM_B)

        verify(timeout = WAIT_MS) { ringtoneB.stop() }
        verify(timeout = WAIT_MS) { ringtoneA.stop() }
        assertFalse("nothing rings, so later calls of the conversation are not skipped", stateManager.isPlayingSound())
        assertNull(stateManager.getCurrentNotificationId())
    }

    @Test
    fun `the play token owner is reported until another alert takes the token or it stops`() {
        val ringtone = mockk<Ringtone>(relaxed = true)
        every { RingtoneManager.getRingtone(any(), any()) } returns ringtone
        player.playSound(CONVERSATION, ID_A, ROOM_A)
        waitUntil { stateManager.getCurrentNotificationId() == ID_A }

        assertTrue(player.ownsPlayToken(ID_A))
        assertFalse(player.ownsPlayToken(ID_B))

        player.playSound(CONVERSATION, ID_B, ROOM_B)
        waitUntil { stateManager.getCurrentNotificationId() == ID_B }

        assertFalse("its flashlight ends itself", player.ownsPlayToken(ID_A))
        assertTrue(player.stopIfOwner(ID_B))
        assertFalse(player.ownsPlayToken(ID_B))
    }

    @Test
    fun `an alert of an ended room is refused before it queues`() {
        registry.end(ROOM_A)

        player.playSound(CONVERSATION, ID_A, ROOM_A)

        assertEquals(0L, stateManager.getCurrentPlayToken())
        verify(exactly = 0) { RingtoneManager.getRingtone(any(), any()) }
    }

    @Test
    fun `stopIfOwner of another alert leaves the playing alert alone`() {
        val ringtoneB = mockk<Ringtone>(relaxed = true)
        every { RingtoneManager.getRingtone(any(), any()) } returns ringtoneB
        player.playSound(CONVERSATION, ID_B, ROOM_B)
        waitUntil { stateManager.getCurrentNotificationId() == ID_B }

        assertFalse(player.stopIfOwner(ID_A))

        assertTrue(stateManager.isPlayingSound())
        assertEquals(ID_B, stateManager.getCurrentNotificationId())
        verify(exactly = 0) { ringtoneB.stop() }
        assertTrue(player.stopIfOwner(ID_B))
        assertFalse(stateManager.isPlayingSound())
        assertEquals(0L, stateManager.getCurrentPlayToken())
        verify(timeout = WAIT_MS) { ringtoneB.stop() }
    }

    @Test
    fun `stopIfOwner of the previous alert leaves a newer alert that owns the pending token`() {
        val ringtoneA = mockk<Ringtone>(relaxed = true)
        val ringtoneB = mockk<Ringtone>(relaxed = true)
        every { RingtoneManager.getRingtone(any(), any()) } returns ringtoneA
        player.playSound(CONVERSATION, ID_A, ROOM_A)
        waitUntil { stateManager.getCurrentNotificationId() == ID_A }
        // B takes the token and its ringtone is still being prepared; the current id is still A's.
        val preparing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        every { RingtoneManager.getRingtone(any(), any()) } answers {
            preparing.countDown()
            proceed.await(WAIT_MS, TimeUnit.MILLISECONDS)
            ringtoneB
        }
        player.playSound(CONVERSATION, ID_B, ROOM_B)
        assertTrue(preparing.await(WAIT_MS, TimeUnit.MILLISECONDS))
        assertEquals(ID_A, stateManager.getCurrentNotificationId())
        val tokenOfB = stateManager.getCurrentPlayToken()

        assertFalse("A no longer owns the token", player.stopIfOwner(ID_A))

        assertEquals(tokenOfB, stateManager.getCurrentPlayToken())
        proceed.countDown()
        waitUntil { stateManager.getCurrentNotificationId() == ID_B }
        assertTrue(stateManager.isPlayingSound())
        verify(exactly = 0) { ringtoneB.stop() }
    }

    @Test
    fun `stopIfOwner stops a pending alert so its ringtone never starts`() {
        val ringtone = mockk<Ringtone>(relaxed = true)
        val preparing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        every { RingtoneManager.getRingtone(any(), any()) } answers {
            preparing.countDown()
            proceed.await(WAIT_MS, TimeUnit.MILLISECONDS)
            ringtone
        }
        player.playSound(CONVERSATION, ID_A, ROOM_A)
        assertTrue(preparing.await(WAIT_MS, TimeUnit.MILLISECONDS))

        assertTrue(player.stopIfOwner(ID_A))
        proceed.countDown()

        verify(timeout = WAIT_MS) { ringtone.stop() }
        assertFalse(stateManager.isPlayingSound())
        verify(exactly = 0) { ringtone.play() }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("condition not reached in ${WAIT_MS}ms", condition())
    }

    private companion object {
        const val CONVERSATION = "peer-1"
        const val ROOM_A = "room-a"
        const val ROOM_B = "room-b"
        const val ID_A = 11
        const val ID_B = 22
        const val WAIT_MS = 5_000L
    }
}
