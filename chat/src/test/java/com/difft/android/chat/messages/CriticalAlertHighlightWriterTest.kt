package com.difft.android.chat.messages

import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.call.state.CriticalAlertRoomRegistry
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Test
import kotlin.concurrent.thread

/**
 * [CriticalAlertHighlightWriter] (#1381): no highlight for an alert of the call being joined or
 * already active; a join's clear is ordered after a write already in progress; and an alert for
 * another call that writes after the join's clear keeps its highlight. An alert for a call that has
 * ended (#1387) keeps its highlight: the room end only silences presentation.
 */
class CriticalAlertHighlightWriterTest {

    private val dbRoomStore = mockk<DBRoomStore>(relaxed = true)
    private val registry = CriticalAlertRoomRegistry()
    private val gate = CriticalAlertCallGate(OnGoingCallStateManager(), registry)
    private val writer = CriticalAlertHighlightWriter(dbRoomStore, gate)

    @Test
    fun `an alert for the joining call writes no highlight`() {
        gate.beginJoin(ROOM_A, CONVERSATION)

        writer.highlightIfUnread(CONVERSATION, ROOM_A, TS_A)

        verify(exactly = 0) { dbRoomStore.setCriticalAlertIfUnread(any(), any()) }
    }

    @Test
    fun `an alert for another call keeps its highlight`() {
        writer.highlightIfUnread(CONVERSATION, ROOM_A, TS_A)

        verify(exactly = 1) { dbRoomStore.setCriticalAlertIfUnread(CONVERSATION, TS_A) }
        verify(exactly = 0) { dbRoomStore.clearCriticalAlert(any()) }
    }

    @Test
    fun `an alert for an ended room still gets its highlight`() {
        // #1387: the call is over but the alert message stays in the chat and keeps its unread highlight.
        registry.end(ROOM_A)

        writer.highlightIfUnread(CONVERSATION, ROOM_A, TS_A)

        verify(exactly = 1) { dbRoomStore.setCriticalAlertIfUnread(CONVERSATION, TS_A) }
    }

    @Test
    fun `a join that begins during the write clears after it`() {
        var join: Thread? = null
        every { dbRoomStore.setCriticalAlertIfUnread(CONVERSATION, TS_A) } answers {
            // The join publishes its marker and clears while this write still holds the conversation lock.
            join = thread {
                gate.beginJoin(ROOM_A, CONVERSATION)
                writer.clearForJoin(CONVERSATION)
            }
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (join?.state != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) Thread.yield()
        }

        writer.highlightIfUnread(CONVERSATION, ROOM_A, TS_A)
        join?.join(5_000)

        verifyOrder {
            dbRoomStore.setCriticalAlertIfUnread(CONVERSATION, TS_A)
            dbRoomStore.clearCriticalAlert(CONVERSATION)
        }
    }

    @Test
    fun `an alert for another room written after the join's clear keeps its highlight`() {
        gate.beginJoin(ROOM_A, CONVERSATION)
        writer.clearForJoin(CONVERSATION)

        writer.highlightIfUnread(CONVERSATION, ROOM_B, TS_B)
        writer.highlightIfUnread(CONVERSATION, ROOM_A, TS_A)

        verifyOrder {
            dbRoomStore.clearCriticalAlert(CONVERSATION)
            dbRoomStore.setCriticalAlertIfUnread(CONVERSATION, TS_B)
        }
        verify(exactly = 1) { dbRoomStore.clearCriticalAlert(CONVERSATION) }
        verify(exactly = 0) { dbRoomStore.setCriticalAlertIfUnread(CONVERSATION, TS_A) }
    }

    private companion object {
        const val CONVERSATION = "group-1"
        const val ROOM_A = "room-1"
        const val ROOM_B = "room-2"
        const val TS_A = 1_000L
        const val TS_B = 2_000L
        const val WAIT_MS = 2_000L
    }
}
