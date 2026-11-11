package com.difft.android.chat.messages

import android.os.Looper
import com.difft.android.base.monitor.ForegroundServiceFailureRecorder
import com.difft.android.base.monitor.KeepAliveHealthCounters
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.chat.websocket.WebSocketManager
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.difft.app.database.WCDBUpdateService
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [31])
class MessageForegroundServiceConnectionTest {

    private val webSocketManager = mockk<WebSocketManager>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(ForegroundServiceStarter::class)
        mockkObject(WCDBUpdateService)
        every { WCDBUpdateService.updatingRooms() } returns Unit
        val entryPoint = mockk<MessageForegroundService.ServiceEntryPoint> {
            every { webSocketManager() } returns webSocketManager
            every { messageServiceManager() } returns mockk(relaxed = true)
            every { foregroundServiceFailureRecorder() } returns mockk<ForegroundServiceFailureRecorder>(relaxed = true)
            every { keepAliveHealthCounters() } returns mockk<KeepAliveHealthCounters>(relaxed = true)
        }
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(any(), MessageForegroundService.ServiceEntryPoint::class.java)
        } returns entryPoint
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun promoted(result: Boolean) {
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) } returns result
    }

    @Test
    fun `a service recreated without onStartCommand still starts the WebSocket`() {
        promoted(true)

        val controller = Robolectric.buildService(MessageForegroundService::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2_000, exactly = 1) { webSocketManager.start() }
        controller.destroy()
    }

    @Test
    fun `a normal start reaches the idempotent start from both entry points`() {
        promoted(true)

        val controller = Robolectric.buildService(MessageForegroundService::class.java).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        verify(timeout = 2_000, exactly = 2) { webSocketManager.start() }
        controller.destroy()
    }

    @Test
    fun `a refused foreground promotion starts nothing from onCreate`() {
        promoted(false)

        val controller = Robolectric.buildService(MessageForegroundService::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)

        verify(exactly = 0) { webSocketManager.start() }
        controller.destroy()
    }
}
