package com.difft.android.chat.setting.sync

import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.setting.ConversationSettingUpdate
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.websocket.api.messages.NotifyConversation
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull






class ConversationSettingsApplierMuteOptionsTest {

    private val q = FakeQueue()
    private val dbRoomStore: DBRoomStore = mockk(relaxed = true)
    private val manager: ConversationSettingsManager = mockk(relaxed = true)
    private val archive: MessageArchiveManager = mockk(relaxed = true)
    private val globalMock: GlobalHiltEntryPoint = mockk(relaxed = true)

    private val applier = ConversationSettingsApplier(
        dbRoomStore = dbRoomStore,
        syncState = q.state,
        messageArchiveManager = archive,
        settingsManager = dagger.Lazy { manager },
    )

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns globalMock
        every { globalMock.myId } returns MY_ID
        q.queue()
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
    }

    private fun snap(version: Int, me: Boolean? = null, all: Boolean? = null, call: Boolean? = null) =
        snapshot(version = version, confidential = null, mute = 1, block = null)
            .copy(allowMentionMe = me, allowMentionAll = all, allowGroupCall = call)

    private fun rowWith(version: Int, me: Boolean? = null, all: Boolean? = null, call: Boolean? = null) =
        room(version = version).apply {
            muteStatus = 1
            blockStatus = 0
            allowMentionMe = me
            allowMentionAll = all
            allowGroupCall = call
        }

    @Test
    fun `newer snapshot writes the three options with the version in one statement and announces them`() = runTest {
        every { dbRoomStore.getRoom(ROOM) } returns rowWith(version = 3)

        applier.applyServerSnapshot(snap(version = 4, me = true, all = true, call = false), SnapshotSource.Pull)

        verify(exactly = 1) {
            dbRoomStore.updateConversationSettings(
                roomId = ROOM,
                muteStatus = null,
                blockStatus = null,
                confidentialMode = null,
                messageExpiry = null,
                messageClearAnchor = null,
                conversationConfigVersion = 4,
                allowMentionMe = true,
                allowMentionAll = true,
                allowGroupCall = false,
            )
        }
        verify {
            manager.emitConversationSettingUpdate(
                ConversationSettingUpdate(ROOM, allowMentionMe = true, allowMentionAll = true, allowGroupCall = false)
            )
        }
    }

    @Test
    fun `only the option that differs from the row is written`() = runTest {
        every { dbRoomStore.getRoom(ROOM) } returns rowWith(version = 3, me = true, all = false, call = false)

        val applied = applier.applyServerSnapshot(snap(version = 4, me = true, all = true, call = false), SnapshotSource.Pull)

        assertEquals(ConversationSettingUpdate(ROOM, allowMentionAll = true), applied)
        verify(exactly = 1) {
            dbRoomStore.updateConversationSettings(
                roomId = ROOM,
                muteStatus = null,
                blockStatus = null,
                confidentialMode = null,
                messageExpiry = null,
                messageClearAnchor = null,
                conversationConfigVersion = 4,
                allowMentionMe = null,
                allowMentionAll = true,
                allowGroupCall = null,
            )
        }
    }

    @Test
    fun `an older snapshot never touches the options`() = runTest {
        every { dbRoomStore.getRoom(ROOM) } returns rowWith(version = 5, me = true)

        applier.applyServerSnapshot(snap(version = 4, me = false, all = true, call = true), SnapshotSource.Pull)
        applier.applyServerSnapshot(snap(version = 4, me = false), SnapshotSource.Push(1, 0, "+other", 2))

        verify(exactly = 0) { dbRoomStore.updateConversationSettings(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a snapshot without the options (older server) leaves them alone`() = runTest {
        every { dbRoomStore.getRoom(ROOM) } returns rowWith(version = 3, me = false, all = true, call = true)

        val applied = applier.applyServerSnapshot(snap(version = 4), SnapshotSource.Pull)

        assertNull(applied)
        verify(exactly = 1) {
            dbRoomStore.updateConversationSettings(
                roomId = ROOM,
                muteStatus = null,
                blockStatus = null,
                confidentialMode = null,
                messageExpiry = null,
                messageClearAnchor = null,
                conversationConfigVersion = 4,
                allowMentionMe = null,
                allowMentionAll = null,
                allowGroupCall = null,
            )
        }
    }

    @Test
    fun `same-version pull restores an option the row lost, a same-version push does not`() = runTest {
        every { dbRoomStore.getRoom(ROOM) } returns rowWith(version = 4, me = false)

        applier.applyServerSnapshot(snap(version = 4, me = true), SnapshotSource.Push(1, 5, "+other", 2))
        verify(exactly = 0) { dbRoomStore.updateConversationSettings(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }

        applier.applyServerSnapshot(snap(version = 4, me = true), SnapshotSource.Pull)
        verify(exactly = 1) {
            dbRoomStore.updateConversationSettings(
                roomId = ROOM,
                muteStatus = null,
                blockStatus = null,
                confidentialMode = null,
                messageExpiry = null,
                messageClearAnchor = null,
                conversationConfigVersion = null,
                allowMentionMe = true,
                allowMentionAll = null,
                allowGroupCall = null,
            )
        }
    }

    @Test
    fun `every snapshot source carries the options`() {
        val body = ConversationSetResponseBody(
            conversation = ROOM, muteStatus = 1, version = 7,
            allowMentionMe = false, allowMentionAll = true, allowGroupCall = true,
        )
        val notify = NotifyConversation(
            version = 7, remark = null, remarkAvatar = null, muteStatus = 1, blockStatus = 0,
            confidentialMode = 0, conversation = ROOM,
            allowMentionMe = false, allowMentionAll = true, allowGroupCall = true,
        )

        for (s in listOf(ServerConfigSnapshot.fromPull(body), ServerConfigSnapshot.fromEcho(body), ServerConfigSnapshot.fromNotify(notify))) {
            assertEquals(Triple(false, true, true), Triple(s.allowMentionMe, s.allowMentionAll, s.allowGroupCall))
        }
    }
}
