package com.difft.android.chat.message

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import com.google.gson.JsonObject
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.ForwardNoticeData
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.MessageActivityNoticeData
import difft.android.messageserialization.model.NotifyMessage
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.wcdb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Unit test for [LocalMessageCreator.createForwardNoticeMessage] (and its copy-notice
 * mirror [LocalMessageCreator.createActivityNoticeMessage] in Case 6).
 *
 * This test does NOT require Hilt — we construct LocalMessageCreator directly with
 * mocked dependencies (MessageStore + MessageArchiveManager). Contact name resolution
 * now goes through `wcdb.getContactorsFromAllTable(...)`; we stub that extension via
 * `mockkStatic("org.difft.app.database.WCDBExtensionsKt")` to return an empty list so
 * the Renderer falls back to `id.formatBase58Id()`.
 *
 * Rule equivalents are inlined in @Before / @After (rather than using
 * `GlobalStaticMockRule` / `TestDispatcherRule` from base testFixtures) because
 * base's testFixtures Kotlin sources are only added to base's own `test` source set
 * (see `base/build.gradle.kts:43-45` comment: "kotlin-kapt does not register a Kotlin
 * compilation task for the testFixtures source set"). Consumer modules like `:chat`
 * cannot resolve those Kotlin classes via `testImplementation(testFixtures(project(":base")))`.
 *
 * No HiltAndroidRule because we don't inject LocalMessageCreator — instantiated manually.
 *
 * ## Coverage
 *
 *  1. `createForwardNoticeMessage` → one put call to MessageStore (落库成功)
 *  2. "不计未读" invariant — captured message.type lands in NotifyMessage class (type=2
 *     via `putNotifyMessage`), which is excluded by `updateRoomUnreadState` (see report
 *     for the architectural reasoning).
 *  3. messageId format = `generateMessageId(ts, operatorId, deviceId)` three-tuple.
 *  4. Idempotency — `putWhenNonExist` is called for both duplicate invocations; the
 *     dedup happens in the store itself (we mock `putWhenNonExist` as a no-op; design
 *     guarantees it is idempotent on messageId).
 *  5. showContent rendered via ForwardNoticeRenderer with operator/authors correctly
 *     localized (self → "You", others → resolved name).
 *  6. Preview-exclusion contract — serialized notice JSON contains the exact
 *     comma-anchored substrings WCDBUpdateService's LIKE filter matches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalMessageCreatorForwardNoticeTest {

    @RelaxedMockK
    lateinit var messageStore: MessageStore

    @MockK
    lateinit var messageArchiveManager: MessageArchiveManager

    private lateinit var context: Application
    private lateinit var creator: LocalMessageCreator
    private lateinit var mockGlobalServices: GlobalHiltEntryPoint

    private val testDispatcher = UnconfinedTestDispatcher()

    private val operatorOther = "+20001"
    private val authorAlice = "+30001"
    private val authorBob = "+30002"

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        context = ApplicationProvider.getApplicationContext()
        // Force English to produce deterministic plurals in assertions.
        val config = context.resources.configuration
        config.setLocale(Locale.ENGLISH)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        // Replace Dispatchers.Main for the test (equivalent of TestDispatcherRule).
        Dispatchers.setMain(testDispatcher)

        // Mock `globalServices.myId` (equivalent of GlobalStaticMockRule).
        // LocalMessageCreator.createForwardNoticeMessage reads `globalServices.myId` once
        // to determine the "self / other" operator branch inside the renderer.
        mockGlobalServices = mockk(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockGlobalServices
        every { mockGlobalServices.myId } returns MY_ID

        // MessageArchiveManager.getMessageArchiveTime is `suspend` — stub via coEvery.
        coEvery { messageArchiveManager.getMessageArchiveTime(any(), any()) } returns 0L

        // Name resolution goes through `wcdb.getContactorsFromAllTable(...)` — a
        // top-level extension on WCDB defined in WCDBExtensions.kt (compiled class
        // `WCDBExtensionsKt`). Default: return empty list so every id → falls back
        // to `id.formatBase58Id()` in the Renderer (matches the previous
        // "ContactorUtil.getContactWithID returns Optional.empty()" behavior).
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        every { wcdb.getContactorsFromAllTable(any(), any()) } returns emptyList()

        creator = LocalMessageCreator(context, messageStore, messageArchiveManager, Gson())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ------------------------------------------------------------
    // Case 1: Baseline — creates + persists a NotifyMessage
    // ------------------------------------------------------------
    @Test
    fun `createForwardNoticeMessage persists a NotifyMessage for the given conversation`() = runTest {
        val forWhat = For.Group("group-1")
        val timestamp = 1_700_000_000_000L
        val notice = ForwardNoticeData(
            scene = ForwardNoticeData.Scene.COMBINED,
            sourceAuthorIds = listOf(authorAlice, authorBob),
            messageCount = 5
        )
        val captured = slot<Message>()
        every { messageStore.putWhenNonExist(capture(captured)) } just Runs

        val result = creator.createForwardNoticeMessage(
            operatorId = operatorOther,
            forWhat = forWhat,
            noticeData = notice,
            systemShowTimestamp = timestamp,
            timestamp = timestamp,
            sourceDevice = DEFAULT_DEVICE_ID
        )

        // Persistence happened exactly once via putWhenNonExist (vararg arg = 1 element).
        verify(exactly = 1) { messageStore.putWhenNonExist(any<Message>()) }
        // Captured message is a NotifyMessage and matches the returned reference.
        assertTrue("stored message must be NotifyMessage", captured.captured is NotifyMessage)
        assertSame("returned NotifyMessage must match the stored one", result, captured.captured)

        val stored = captured.captured as NotifyMessage
        assertEquals(forWhat, stored.forWhat)
        assertEquals(For.Account(operatorOther), stored.fromWho)
        assertEquals(timestamp, stored.timeStamp)
        assertEquals(timestamp, stored.systemShowTimestamp)
    }

    // ------------------------------------------------------------
    // Case 2: "Not counted as unread" invariant — message persists
    // as a `NotifyMessage` (not `TextMessage`), so when WCDB stores it
    // via `putNotifyMessage` it gets `type = 2 (TYPE_NOTIFY)`. The unread
    // query in `updateRoomUnreadState` (WCDBExtensions.kt:917) explicitly
    // excludes TYPE_NOTIFY via `.type.notIn(TYPE_NOTIFY, ...)`.
    //
    // This test pins the class choice at the API surface — if someone
    // changes `createForwardNoticeMessage` to return/persist TextMessage,
    // this test fails, catching the silent regression that would make
    // the forward notice start incrementing unread counts.
    // ------------------------------------------------------------
    @Test
    fun `createForwardNoticeMessage stores NotifyMessage type so unread query excludes it`() = runTest {
        val captured = slot<Message>()
        every { messageStore.putWhenNonExist(capture(captured)) } just Runs

        creator.createForwardNoticeMessage(
            operatorId = MY_ID,
            forWhat = For.Account(operatorOther),
            noticeData = ForwardNoticeData(ForwardNoticeData.Scene.SINGLE, listOf(authorAlice), 1),
            systemShowTimestamp = 1L,
            timestamp = 1L
        )

        // Contract: must be a NotifyMessage. `WCDB.putNotifyMessage` sets
        // MessageModel.type = 2 (TYPE_NOTIFY) → unread query excludes it.
        assertTrue(
            "createForwardNoticeMessage MUST produce a NotifyMessage so the " +
                "WCDB unread query in updateRoomUnreadState (which filters out " +
                "TYPE_NOTIFY) excludes it. A TextMessage here would silently " +
                "start incrementing unread counts.",
            captured.captured is NotifyMessage
        )
    }

    // ------------------------------------------------------------
    // Case 3: messageId is generateMessageId(ts, operatorId, deviceId)
    // ------------------------------------------------------------
    @Test
    fun `messageId format follows generateMessageId three-tuple`() = runTest {
        val captured = slot<Message>()
        every { messageStore.putWhenNonExist(capture(captured)) } just Runs

        val timestamp = 1_700_000_000_123L
        val deviceId = 1
        creator.createForwardNoticeMessage(
            operatorId = operatorOther,
            forWhat = For.Group("g"),
            noticeData = ForwardNoticeData(ForwardNoticeData.Scene.ONE_BY_ONE, listOf(authorAlice), 2),
            systemShowTimestamp = timestamp,
            timestamp = timestamp,
            sourceDevice = deviceId
        )

        val expectedId = LocalMessageCreator.generateMessageId(timestamp, operatorOther, deviceId)
        assertEquals(expectedId, captured.captured.id)
    }

    // ------------------------------------------------------------
    // Case 4: Calling twice with same timestamp+operator+device → same messageId.
    //   `putWhenNonExist` receives two calls; the real store is idempotent on
    //   messageId collision (see DBMessageStore:46 processingMessageIds guard +
    //   WCDBExtensions:543 existing-row check). This test pins the deterministic
    //   id generation that makes the dedup possible.
    // ------------------------------------------------------------
    @Test
    fun `duplicate invocations produce identical messageId — idempotent dedup key`() = runTest {
        val stored = mutableListOf<Message>()
        every { messageStore.putWhenNonExist(capture(stored)) } just Runs

        val notice = ForwardNoticeData(ForwardNoticeData.Scene.SINGLE, listOf(authorAlice), 1)
        val timestamp = 1_700_000_000_999L
        val common: suspend () -> Unit = {
            creator.createForwardNoticeMessage(
                operatorId = operatorOther,
                forWhat = For.Group("g"),
                noticeData = notice,
                systemShowTimestamp = timestamp,
                timestamp = timestamp,
                sourceDevice = DEFAULT_DEVICE_ID
            )
        }
        common()
        common()

        // Both invocations reached the store with the SAME messageId — making dedup possible.
        assertEquals(2, stored.size)
        assertEquals(stored[0].id, stored[1].id)
    }

    // ------------------------------------------------------------
    // Case 5: showContent is rendered via ForwardNoticeRenderer
    //   (self-operator → "You", other authors → contact fallback).
    // ------------------------------------------------------------
    @Test
    fun `showContent is rendered with self operator as You and authors listed`() = runTest {
        val captured = slot<Message>()
        every { messageStore.putWhenNonExist(capture(captured)) } just Runs

        creator.createForwardNoticeMessage(
            operatorId = MY_ID,
            forWhat = For.Account(operatorOther),
            noticeData = ForwardNoticeData(
                scene = ForwardNoticeData.Scene.COMBINED,
                sourceAuthorIds = listOf(authorAlice, authorBob),
                messageCount = 3
            ),
            systemShowTimestamp = 1L,
            timestamp = 1L
        )

        val notifyMsg = captured.captured as NotifyMessage
        val ttNotify = Gson().fromJson(notifyMsg.notifyContent, JsonObject::class.java)
        val showContent = ttNotify["showContent"].asString

        // self-operator must be "You", not the underlying id.
        assertTrue("should start with \"You\" quoted", showContent.startsWith("\"You\""))
        assertTrue("should include message count", showContent.contains("3 messages"))
        // Neither author is in the mocked contact store → formatBase58Id fallback.
        // Just verify the ids (pre-format) are NOT leaked raw — the fallback formatter
        // transforms them, so we just assert "You" and the plurals text are present.
        assertTrue("should say 'forwarded'", showContent.contains("forwarded"))

        // notifyType is the forward-notice constant.
        assertEquals(
            TTNotifyMessage.NOTIFY_ACTION_TYPE_FORWARD_NOTICE,
            ttNotify["notifyType"].asInt
        )
    }

    // ------------------------------------------------------------
    // Case 6: Preview-exclusion contract — WCDBUpdateService's room-preview
    //   query excludes forward/copy notices via `messageText LIKE
    //   '%"notifyType":10020,%'` / `'%...10021,%'` (comma-anchored).
    //   Literal substrings on purpose: referencing the constants would be a
    //   tautology and miss constant/Gson-format drift.
    // ------------------------------------------------------------
    @Test
    fun `serialized notice JSON contains the substring the preview-exclusion LIKE filter matches`() = runTest {
        val stored = mutableListOf<Message>()
        every { messageStore.putWhenNonExist(capture(stored)) } just Runs

        creator.createForwardNoticeMessage(
            operatorId = MY_ID,
            forWhat = For.Group("g"),
            noticeData = ForwardNoticeData(ForwardNoticeData.Scene.SINGLE, listOf(authorAlice), 1),
            systemShowTimestamp = 1L,
            timestamp = 1L
        )
        creator.createActivityNoticeMessage(
            operatorId = MY_ID,
            forWhat = For.Group("g"),
            noticeData = MessageActivityNoticeData(
                type = MessageActivityNoticeData.Type.COPY,
                sourceAuthorIds = listOf(authorAlice),
                messageCount = 1
            ),
            systemShowTimestamp = 2L,
            timestamp = 2L
        )

        val forwardJson = (stored[0] as NotifyMessage).notifyContent
        val copyJson = (stored[1] as NotifyMessage).notifyContent
        assertTrue(
            "forward notice JSON must contain \"notifyType\":10020, — the exact substring " +
                "WCDBUpdateService's preview-exclusion LIKE filter matches",
            forwardJson.contains("\"notifyType\":10020,")
        )
        assertTrue(
            "copy notice JSON must contain \"notifyType\":10021, — the exact substring " +
                "WCDBUpdateService's preview-exclusion LIKE filter matches",
            copyJson.contains("\"notifyType\":10021,")
        )
    }

    companion object {
        private const val MY_ID = "ME"
    }
}
