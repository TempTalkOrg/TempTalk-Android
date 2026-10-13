package com.difft.android.call.service

import app.cash.turbine.test
import com.difft.android.call.data.CallStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rules of the ongoing-call notification decision layer. Plain JUnit — the layer is deliberately
 * free of Android and injection so these can run without Robolectric or Hilt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallNotificationRenderTest {

    @Test
    fun `renders the already-known call status once the service reaches the foreground`() = runTest {
        // The collector starts in onCreate, before the first onStartCommand. A combine over
        // StateFlows does not re-emit an unchanged value, so if the foreground flag were merely
        // read, the whole waiting-for-answer window would keep the plain starting notification
        // and never gain a hang-up action.
        val status = MutableStateFlow(CallStatus.CALLING)
        val connectedAt = MutableStateFlow(0L)
        val foregroundStarted = MutableStateFlow(false)
        val roomId = MutableStateFlow<String?>("room-1")

        callNotificationStates(status, connectedAt, foregroundStarted, roomId) { false }.test {
            assertNull("nothing may be posted before the service is in the foreground", awaitItem())

            foregroundStarted.value = true

            val state = awaitItem()
            assertEquals(CallStatus.CALLING, state?.status)
        }
    }

    @Test
    fun `renders nothing while the call status is still unknown`() = runTest {
        val status = MutableStateFlow(CallStatus.UNKNOWN)
        val connectedAt = MutableStateFlow(1_000L)
        val foregroundStarted = MutableStateFlow(true)
        val roomId = MutableStateFlow<String?>("room-1")

        callNotificationStates(status, connectedAt, foregroundStarted, roomId) { false }.test {
            assertNull(awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `renders nothing once teardown has begun, so an ended call is not re-posted`() = runTest {
        val status = MutableStateFlow(CallStatus.CONNECTED)
        val connectedAt = MutableStateFlow(1_000L)
        val foregroundStarted = MutableStateFlow(true)
        val roomId = MutableStateFlow<String?>("room-1")

        callNotificationStates(status, connectedAt, foregroundStarted, roomId) { true }.test {
            assertNull(awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `renders nothing until the call has a room id, so the hang-up action can route`() = runTest {
        // An outgoing 1v1 call is CALLING as soon as the view model exists, but the room id only
        // arrives with the create-call response. A notification posted in that window would carry
        // a hang-up button whose broadcast the handler drops for want of a room id.
        val status = MutableStateFlow(CallStatus.CALLING)
        val connectedAt = MutableStateFlow(0L)
        val foregroundStarted = MutableStateFlow(true)
        val roomId = MutableStateFlow<String?>(null)

        callNotificationStates(status, connectedAt, foregroundStarted, roomId) { false }.test {
            assertNull("nothing may be posted while the hang-up action cannot route", awaitItem())

            roomId.value = "room-1"

            assertEquals(CallNotificationState(CallStatus.CALLING, 0L), awaitItem())
        }
    }

    @Test
    fun `renders nothing for a status arriving after the room id was cleared`() = runTest {
        // Teardown clears the room id and resets the ending flag, but the view model's status
        // bridge outlives cleanup. Without the room-id gate a late write would re-post a live
        // call notification over a call that already ended.
        val status = MutableStateFlow(CallStatus.CONNECTED)
        val connectedAt = MutableStateFlow(1_000L)
        val foregroundStarted = MutableStateFlow(true)
        val roomId = MutableStateFlow<String?>(null)

        callNotificationStates(status, connectedAt, foregroundStarted, roomId) { false }.test {
            assertNull(awaitItem())

            status.value = CallStatus.RECONNECTED

            expectNoEvents()
        }
    }

    @Test
    fun `re-renders with the connect instant when it is stamped after the status turns connected`() = runTest {
        // 1v1 ordering: CONNECTED arrives before the duration timer stamps the instant.
        val status = MutableStateFlow(CallStatus.CONNECTED)
        val connectedAt = MutableStateFlow(0L)
        val foregroundStarted = MutableStateFlow(true)
        val roomId = MutableStateFlow<String?>("room-1")

        callNotificationStates(status, connectedAt, foregroundStarted, roomId) { false }.test {
            assertEquals(CallNotificationState(CallStatus.CONNECTED, 0L), awaitItem())

            connectedAt.value = 1_700_000_000_000L

            assertEquals(
                CallNotificationState(CallStatus.CONNECTED, 1_700_000_000_000L),
                awaitItem()
            )
        }
    }

    @Test
    fun `maps each call status to the notification text it should show`() {
        assertEquals(
            com.difft.android.call.R.string.call_notification_in_progress,
            contentTextRes(CallStatus.CONNECTED)
        )
        assertEquals(
            com.difft.android.call.R.string.call_notification_in_progress,
            contentTextRes(CallStatus.RECONNECTED)
        )
        assertEquals(
            com.difft.android.call.R.string.call_status_calling,
            contentTextRes(CallStatus.CALLING)
        )
        // Terminal states must not read as if the call were still coming up.
        assertEquals(
            com.difft.android.call.R.string.call_disconnected_title,
            contentTextRes(CallStatus.DISCONNECTED)
        )
        assertEquals(
            com.difft.android.call.R.string.call_disconnected_title,
            contentTextRes(CallStatus.CONNECTED_FAILED)
        )
        assertEquals(
            com.difft.android.call.R.string.call_disconnected_title,
            contentTextRes(CallStatus.RECONNECT_FAILED)
        )
        assertEquals(
            com.difft.android.call.R.string.call_connecting_title,
            contentTextRes(CallStatus.JOINING)
        )
        assertEquals(
            com.difft.android.call.R.string.call_connecting_title,
            contentTextRes(CallStatus.RECONNECTING)
        )
    }
}
