package com.difft.android.call.core

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.difft.android.call.CallIntent
import com.difft.android.call.exception.CallPreconditionException
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread







@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CallRoomControllerConcurrencyTest {

    private lateinit var mockRoom: Room

    @Before
    fun setUp() {
        mockkObject(LiveKit)
        mockRoom = mockk(relaxed = true)
        every { LiveKit.create(any(), any(), any()) } returns mockRoom
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun buildController(): CallRoomController = CallRoomController(
        appContext = ApplicationProvider.getApplicationContext(),
        callIntent = CallIntent(Intent()),
        audioHandler = mockk<AudioSwitchHandler>(relaxed = true),
        audioProcessor = null,
        e2eeEnable = false,
        proxyConfigProvider = mockk(relaxed = true),
        decryptCallMKey = { _, _ -> null },
    )

    
    @Test
    fun `T10 - release before create is a no-op and never creates a room`() {
        val ctl = buildController()

        ctl.disconnectAndRelease()

        assertFalse("room must not be created", ctl.isRoomInitialized())
        assertTrue("releaseIntended is set even before create", ctl.isReleaseIntended())
        assertFalse("released stays false: there was no room to release", ctl.isReleased())
        verify(exactly = 0) { LiveKit.create(any(), any(), any()) }
    }

    
    @Test
    fun `T14a - concurrent createRoom creates exactly one room shared by both callers`() {
        val ctl = buildController()
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Room>())

        val t1 = thread { start.await(); results.add(ctl.createRoom()) }
        val t2 = thread { start.await(); results.add(ctl.createRoom()) }
        start.countDown()
        t1.join()
        t2.join()

        verify(exactly = 1) { LiveKit.create(any(), any(), any()) }
        assertEquals(2, results.size)
        assertSame("both callers get the same Room instance", results[0], results[1])
        assertTrue(ctl.isRoomInitialized())
    }

    
    @Test
    fun `T13 - release racing create releases the room exactly once`() {
        val enteredCreate = CountDownLatch(1)
        val holdCreate = CountDownLatch(1)
        every { LiveKit.create(any(), any(), any()) } answers {
            enteredCreate.countDown()
            holdCreate.await()
            mockRoom
        }
        val ctl = buildController()

        
        val tCreate = thread { ctl.createRoom() }
        enteredCreate.await() 

        
        val tRelease = thread { ctl.disconnectAndRelease() }

        
        
        while (!ctl.isReleaseIntended()) { Thread.yield() }

        
        holdCreate.countDown()
        tCreate.join()
        tRelease.join()

        verify(exactly = 1) { mockRoom.release() }
        assertTrue("room was created", ctl.isRoomInitialized())
        assertTrue("room was released exactly once", ctl.isReleased())
    }

    
    @Test
    fun `T16 - isReleaseIntended is observed lock-free while createRoom holds the lock`() {
        val enteredCreate = CountDownLatch(1)
        val holdCreate = CountDownLatch(1)
        every { LiveKit.create(any(), any(), any()) } answers {
            enteredCreate.countDown()
            holdCreate.await()
            mockRoom
        }
        val ctl = buildController()

        val tCreate = thread { ctl.createRoom() }
        enteredCreate.await() 

        val tRelease = thread { ctl.disconnectAndRelease() }

        
        
        
        while (!ctl.isReleaseIntended()) { Thread.yield() }
        assertTrue(
            "releaseIntended must be observable lock-free while createRoom still holds roomLock",
            ctl.isReleaseIntended(),
        )

        holdCreate.countDown()
        tCreate.join()
        tRelease.join()

        
        verify(exactly = 1) { mockRoom.release() }
        assertTrue(ctl.isReleased())
    }

    
    
    @Test
    fun `room getter throws before createRoom`() {
        val ctl = buildController()
        val ex = assertThrows(CallPreconditionException::class.java) { ctl.room }
        assertTrue(ex.message!!.contains("before createRoom"))
    }

    @Test
    fun `room getter throws after release instead of returning the dead room`() {
        val ctl = buildController()
        ctl.createRoom()
        assertSame("getter returns the live room before release", mockRoom, ctl.room)

        ctl.disconnectAndRelease()

        val ex = assertThrows(CallPreconditionException::class.java) { ctl.room }
        assertTrue("post-release access must fail loud", ex.message!!.contains("after release"))
    }

    
    
    @Test
    fun `roomStateOrNull is null before create, live state after create, null after release`() {
        val ctl = buildController()
        assertNull("null before createRoom instead of throwing", ctl.roomStateOrNull())

        every { mockRoom.state } returns Room.State.CONNECTED
        ctl.createRoom()
        assertEquals(Room.State.CONNECTED, ctl.roomStateOrNull())

        ctl.disconnectAndRelease()
        assertNull("null after release instead of throwing", ctl.roomStateOrNull())
    }

    @Test
    fun `isRoomDisconnected is true before create, tracks state after create, true after release`() {
        val ctl = buildController()
        assertTrue("true before createRoom (nothing to tear down)", ctl.isRoomDisconnected())

        every { mockRoom.state } returns Room.State.CONNECTED
        ctl.createRoom()
        assertFalse("false while the live room is CONNECTED", ctl.isRoomDisconnected())

        every { mockRoom.state } returns Room.State.DISCONNECTED
        assertTrue("true once the live room reports DISCONNECTED", ctl.isRoomDisconnected())

        ctl.disconnectAndRelease()
        assertTrue("true after release (gone room reads as disconnected)", ctl.isRoomDisconnected())
    }

    @Test
    fun `hasRemoteParticipants is false before create and after release, true with remotes`() {
        val ctl = buildController()
        assertFalse("false before createRoom instead of throwing", ctl.hasRemoteParticipants())

        every { mockRoom.remoteParticipants } returns
            mapOf(Participant.Identity("remote-1") to mockk<RemoteParticipant>(relaxed = true))
        ctl.createRoom()
        assertTrue("true while a remote participant is present", ctl.hasRemoteParticipants())

        ctl.disconnectAndRelease()
        assertFalse("false after release instead of throwing", ctl.hasRemoteParticipants())
    }

    @Test
    fun `hasRemoteParticipants is false when only self is in the room`() {
        val ctl = buildController()
        every { mockRoom.remoteParticipants } returns emptyMap()
        ctl.createRoom()

        assertFalse("empty remotes → false (preserves only-self switch rejection)", ctl.hasRemoteParticipants())
    }

    @Test
    fun `disconnectQuietly is a no-op before create and never throws`() {
        val ctl = buildController()

        ctl.disconnectQuietly() 

        assertFalse("no room created by a quiet disconnect", ctl.isRoomInitialized())
        verify(exactly = 0) { mockRoom.disconnect() }
    }

    @Test
    fun `disconnectQuietly disconnects the live room without marking it released`() {
        val ctl = buildController()
        ctl.createRoom()

        ctl.disconnectQuietly()

        verify(exactly = 1) { mockRoom.disconnect() }
        
        
        assertFalse("disconnectQuietly must not release the room", ctl.isReleased())
        assertSame(mockRoom, ctl.room)
    }

    @Test
    fun `disconnectQuietly after release does not disconnect the dead room again`() {
        val ctl = buildController()
        ctl.createRoom()
        ctl.disconnectAndRelease() 

        ctl.disconnectQuietly() 

        verify(exactly = 1) { mockRoom.disconnect() }
        assertTrue(ctl.isReleased())
    }
}
