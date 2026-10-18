package com.difft.android.chat.setting.sync

import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.setting.ConversationSettingUpdate
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.messageserialization.db.store.DBRoomStore
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

/**
 * The gating table of [ConversationSettingsApplier] (design §3.3) plus the interleavings the
 * cross-platform review found: stale echo after a newer push, same-version reconcile, same-version
 * push adjudication, and version comparison against the version read on entry.
 */
class ConversationSettingsApplierTest {

    private val field = SyncField.CONFIDENTIAL_MODE
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
        every { archive.getDefaultMessageArchiveTime() } returns 86_400L
        q.queue()
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
    }

    private fun roomIs(confidential: Int, version: Int) {
        every { dbRoomStore.getRoom(ROOM) } returns room(confidential, version)
    }

    private fun verifyWrite(confidential: Int?, version: Int?, mute: Int? = 0) {
        verify(exactly = 1) {
            dbRoomStore.updateConversationSettings(
                roomId = ROOM,
                muteStatus = mute,
                blockStatus = any(),
                confidentialMode = confidential,
                messageExpiry = any(),
                messageClearAnchor = any(),
                conversationConfigVersion = version,
            )
        }
    }

    private fun verifyNoWrite() {
        verify(exactly = 0) { dbRoomStore.updateConversationSettings(any(), any(), any(), any(), any(), any(), any()) }
    }

    // --- no pending ---

    @Test
    fun `newer pull with nothing pending writes every field and the version in one statement`() = runTest {
        roomIs(confidential = 0, version = 10)

        applier.applyServerSnapshot(snapshot(version = 12, confidential = 1, expiry = -1), SnapshotSource.Pull)

        verifyWrite(confidential = 1, version = 12)
        verify { dbRoomStore.updateConversationSettings(ROOM, any(), any(), 1, 86_400L, any(), 12) }
        verify { manager.emitConversationSettingUpdate(ConversationSettingUpdate(ROOM, 0, 0, 1, 86_400L, null)) }
    }

    @Test
    fun `applyServerSnapshot returns what it wrote and can stay silent for the bulk sync`() = runTest {
        roomIs(confidential = 0, version = 10)

        val applied = applier.applyServerSnapshot(snapshot(version = 11, confidential = 1), SnapshotSource.Pull, announce = false)

        assertEquals(ConversationSettingUpdate(ROOM, 0, 0, 1, null, null), applied)
        verify(exactly = 0) { manager.emitConversationSettingUpdate(any<ConversationSettingUpdate>()) }
    }

    @Test
    fun `same-version pull or echo re-applies the managed field but a same-version push does not`() = runTest {
        // Recovery path: the row was written locally but the job never landed; the pull restores the server value.
        roomIs(confidential = 1, version = 10)
        applier.applyServerSnapshot(snapshot(version = 10, confidential = 0), SnapshotSource.Pull)
        verifyWrite(confidential = 0, version = null)

        applier.applyServerSnapshot(snapshot(version = 10, confidential = 0), SnapshotSource.Echo)
        verify(exactly = 2) { dbRoomStore.updateConversationSettings(any(), any(), any(), any(), any(), any(), any()) }

        applier.applyServerSnapshot(snapshot(version = 10, confidential = 0), push(ts = 1, changeType = field.changeType))
        verify(exactly = 2) { dbRoomStore.updateConversationSettings(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a snapshot that changes nothing writes nothing and emits nothing, but a newer version is still recorded`() = runTest {
        every { dbRoomStore.getRoom(ROOM) } returns room(confidential = 1, version = 10).apply { muteStatus = 0; blockStatus = 0 }

        applier.applyServerSnapshot(snapshot(version = 10, confidential = 1), SnapshotSource.Pull)
        verifyNoWrite()
        verify(exactly = 0) { manager.emitConversationSettingUpdate(any<ConversationSettingUpdate>()) }

        applier.applyServerSnapshot(snapshot(version = 11, confidential = 1), SnapshotSource.Pull)
        verifyWrite(confidential = null, version = 11, mute = null)
        verify(exactly = 0) { manager.emitConversationSettingUpdate(any<ConversationSettingUpdate>()) }
    }

    @Test
    fun `older snapshot is ignored entirely`() = runTest {
        roomIs(confidential = 1, version = 10)
        applier.applyServerSnapshot(snapshot(version = 9, confidential = 0), SnapshotSource.Pull)
        applier.applyServerSnapshot(snapshot(version = 9, confidential = 0), push(ts = 999, changeType = field.changeType))
        verifyNoWrite()
    }

    @Test
    fun `unversioned snapshot applies like before the gate existed and never advances the version`() = runTest {
        roomIs(confidential = 1, version = 10)
        applier.applyServerSnapshot(snapshot(version = 0, confidential = 0, mute = 1), push(ts = 1, changeType = 0))
        verifyWrite(confidential = 0, version = null, mute = 1)

        // While pending it is still gated like any other snapshot.
        q.queue(pendingSpec("a", ts = 1_000))
        applier.applyServerSnapshot(snapshot(version = 0, confidential = 1, mute = 0), SnapshotSource.Pull)
        verify(exactly = 1) { dbRoomStore.updateConversationSettings(ROOM, 0, any(), null, any(), any(), null) }
    }

    @Test
    fun `push triggered by this device is skipped`() = runTest {
        roomIs(confidential = 0, version = 10)
        applier.applyServerSnapshot(
            snapshot(version = 11, confidential = 1),
            SnapshotSource.Push(serverTimestamp = 5, changeType = field.changeType, operator = MY_ID, operatorDeviceId = DEFAULT_DEVICE_ID),
        )
        verifyNoWrite()
    }

    // --- pending ---

    @Test
    fun `pending keeps the managed field but still advances other fields and the version from a pull`() = runTest {
        roomIs(confidential = 1, version = 10)
        q.queue(pendingSpec("a", ts = 1_000))

        applier.applyServerSnapshot(snapshot(version = 11, confidential = 0, mute = 1), SnapshotSource.Pull)

        verifyWrite(confidential = null, version = 11, mute = 1)
    }

    @Test
    fun `pending and a later push that changed this field lets the server win, even at the same version`() = runTest {
        // Pull V11 already brought the version while the field stayed gated; the V11 push still adjudicates.
        roomIs(confidential = 1, version = 11)
        q.queue(pendingSpec("a", ts = 1_000))

        applier.applyServerSnapshot(snapshot(version = 11, confidential = 0), push(ts = 2_000, changeType = field.changeType))

        // Only the adjudicated field: a same-version push is not authoritative for the others.
        verifyWrite(confidential = 0, version = null, mute = null)
    }

    @Test
    fun `a field push behind the local version is ignored even while pending`() = runTest {
        // Out-of-order replay: the V12 push (enable, ts 3000) already won; the older V11 push
        // (disable, ts 2000) must not re-open the conflict and hand the local SEND its old value back.
        roomIs(confidential = 1, version = 12)
        q.queue(pendingSpec("a", ts = 1_000))

        applier.applyServerSnapshot(snapshot(version = 11, confidential = 0), push(ts = 2_000, changeType = field.changeType))

        verifyNoWrite()
    }

    @Test
    fun `pending and an earlier push or a push for another field leaves the managed field alone`() = runTest {
        roomIs(confidential = 1, version = 10)
        q.queue(pendingSpec("a", ts = 1_000))

        applier.applyServerSnapshot(snapshot(version = 11, confidential = 0), push(ts = 500, changeType = field.changeType))
        applier.applyServerSnapshot(snapshot(version = 12, confidential = 0), push(ts = 5_000, changeType = 0))

        verify(exactly = 2) { dbRoomStore.updateConversationSettings(ROOM, any(), any(), null, any(), any(), any()) }
        verify(exactly = 0) { dbRoomStore.updateConversationSettings(ROOM, any(), any(), 0, any(), any(), any()) }
    }

    @Test
    fun `an unexpired in-memory intent gates like a queued job`() = runTest {
        roomIs(confidential = 1, version = 10)
        q.state.recordIntent(ROOM, field, ts = 1_000)

        applier.applyServerSnapshot(snapshot(version = 11, confidential = 0), SnapshotSource.Pull)

        verifyWrite(confidential = null, version = 11)
    }

    // --- applyFinal ---

    @Test
    fun `final snapshot below the local or accepted version needs reconcile and writes nothing for the field`() = runTest {
        // v4 review: the job's echo (V10) arrives after a newer push (V11) already landed.
        roomIs(confidential = 0, version = 11)
        assertEquals(FinalOutcome.NEEDS_RECONCILE, applier.applyFinal(snapshot(version = 10, confidential = 1), "a", 1_000, minVersion = 10))
        verifyNoWrite()

        // v8 review: GET read a replica that does not yet contain the accepted V11 write. A lagging
        // read is trusted for nothing, not even the other fields (Bugbot on #1337).
        roomIs(confidential = 1, version = 10)
        assertEquals(FinalOutcome.NEEDS_RECONCILE, applier.applyFinal(snapshot(version = 10, confidential = 0, mute = 1), "a", 1_000, minVersion = 11))
        verifyNoWrite()
    }

    @Test
    fun `final snapshot at the same version aligns the field when the job is the newest intent`() = runTest {
        // v5 review: a pull already advanced the version while the field was gated; the reconcile GET returns the same version.
        roomIs(confidential = 1, version = 10)
        q.queue(pendingSpec("a", ts = 1_000))

        val outcome = applier.applyFinal(snapshot(version = 10, confidential = 0), "a", 1_000, minVersion = 0)

        assertEquals(FinalOutcome.COMPLETED, outcome)
        verifyWrite(confidential = 0, version = null)
        // Finalized: the job no longer gates the room even though the framework has not deleted it yet.
        roomIs(confidential = 0, version = 10)
        applier.applyServerSnapshot(snapshot(version = 12, confidential = 1), SnapshotSource.Pull)
        verify(exactly = 1) { dbRoomStore.updateConversationSettings(ROOM, any(), any(), 1, any(), any(), 12) }
    }

    @Test
    fun `final write is refused when a newer job or intent exists`() = runTest {
        roomIs(confidential = 0, version = 10)
        q.queue(pendingSpec("a", ts = 1_000), pendingSpec("b", ts = 2_000))
        assertEquals(FinalOutcome.SUPERSEDED, applier.applyFinal(snapshot(version = 11, confidential = 1), "a", 1_000, minVersion = 11))

        q.queue(pendingSpec("a", ts = 1_000))
        q.state.recordIntent(ROOM, field, ts = 3_000)
        assertEquals(FinalOutcome.SUPERSEDED, applier.applyFinal(snapshot(version = 12, confidential = 1), "a", 1_000, minVersion = 11))

        verify(exactly = 0) { dbRoomStore.updateConversationSettings(ROOM, any(), any(), 1, any(), any(), any()) }
        // Other fields and the version still advance so the floor is reflected locally.
        verify(exactly = 2) { dbRoomStore.updateConversationSettings(ROOM, any(), any(), null, any(), any(), any()) }
    }

    private fun push(ts: Long, changeType: Int) =
        SnapshotSource.Push(serverTimestamp = ts, changeType = changeType, operator = "+other", operatorDeviceId = 2)
}
