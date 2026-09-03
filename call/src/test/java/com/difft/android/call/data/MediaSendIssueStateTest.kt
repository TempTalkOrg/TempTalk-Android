package com.difft.android.call.data

import io.livekit.android.room.MediaSendConnectionState
import io.livekit.android.room.Room
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Direct regression tests for [MediaSendIssueState.resolve] — the doc's unified priority
 * mapping that fixes both field-reported false alarms. Pure JVM, no Robolectric: the two
 * inputs are plain SDK enums.
 */
class MediaSendIssueStateTest {

    @Test
    fun `disconnected room hides everything regardless of send state`() {
        MediaSendConnectionState.entries.forEach { sendState ->
            assertEquals(
                "DISCONNECTED must always resolve to NONE (sendState=$sendState)",
                MediaSendIssueState.NONE,
                MediaSendIssueState.resolve(Room.State.DISCONNECTED, sendState),
            )
        }
    }

    @Test
    fun `room reconnecting joins the connection presentation regardless of send state`() {
        MediaSendConnectionState.entries.forEach { sendState ->
            assertEquals(
                "RECONNECTING must always resolve to CONNECTION_RECOVERING (sendState=$sendState)",
                MediaSendIssueState.CONNECTION_RECOVERING,
                MediaSendIssueState.resolve(Room.State.RECONNECTING, sendState),
            )
        }
    }

    @Test
    fun `room recovering while connected joins the connection presentation`() {
        // Doc acceptance (network loss): the SDK reports ROOM_RECOVERING while the room state
        // flow still says CONNECTED — must show the connection hint, never the media hint.
        assertEquals(
            MediaSendIssueState.CONNECTION_RECOVERING,
            MediaSendIssueState.resolve(Room.State.CONNECTED, MediaSendConnectionState.ROOM_RECOVERING),
        )
    }

    @Test
    fun `connecting never warns even when the room is already connected`() {
        // Doc acceptance (normal join): publisher first negotiation is not a degradation.
        assertEquals(
            MediaSendIssueState.NONE,
            MediaSendIssueState.resolve(Room.State.CONNECTED, MediaSendConnectionState.CONNECTING),
        )
    }

    @Test
    fun `uplink recovering while the room is healthy warns`() {
        assertEquals(
            MediaSendIssueState.SEND_RECOVERING,
            MediaSendIssueState.resolve(Room.State.CONNECTED, MediaSendConnectionState.RECOVERING),
        )
    }

    @Test
    fun `uplink failed shares the recovering presentation`() {
        assertEquals(
            MediaSendIssueState.SEND_RECOVERING,
            MediaSendIssueState.resolve(Room.State.CONNECTED, MediaSendConnectionState.FAILED),
        )
    }

    @Test
    fun `healthy or idle uplink shows nothing`() {
        assertEquals(
            MediaSendIssueState.NONE,
            MediaSendIssueState.resolve(Room.State.CONNECTED, MediaSendConnectionState.IDLE),
        )
        assertEquals(
            MediaSendIssueState.NONE,
            MediaSendIssueState.resolve(Room.State.CONNECTED, MediaSendConnectionState.CONNECTED),
        )
    }

    @Test
    fun `uplink degradation reported while the room is still connecting stays silent`() {
        // The media hint requires a CONNECTED room; pre-connect states own their own UI.
        assertEquals(
            MediaSendIssueState.NONE,
            MediaSendIssueState.resolve(Room.State.CONNECTING, MediaSendConnectionState.RECOVERING),
        )
    }
}
