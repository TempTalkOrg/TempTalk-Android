package com.difft.android.chat.translate

import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.ChatMessageListBehavior
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.setting.ConversationSettingUpdate
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.translate.model.DownloadTrigger
import com.difft.android.chat.translate.model.ModelState
import com.difft.android.chat.translate.model.TranslateModelStore
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import difft.android.messageserialization.For
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.TranslateTargetLanguage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.difft.app.database.hydration.MessageHydration
import org.difft.app.database.hydration.MessageHydrator
import org.difft.app.database.hydration.MessageSubData
import org.difft.app.database.models.MessageModel
import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Shared fixture for the [AutoTranslateCoordinator] suites.
 *
 * The queue is a MockK whose `completions` is a real SharedFlow, so these tests drive exactly the
 * delivery channel production uses: a result reaches the page through the ONE long-lived collector,
 * never through whoever happened to await a handle.
 *
 * Everything runs on real dispatchers. The coordinator mixes its own scope with explicit
 * `Dispatchers.IO` hops (the stored-setting read, hydration, every DB write), so a virtual clock
 * would only cover half the work and make the ordering of the rest thread-dependent; assertions
 * therefore use bounded polling (`awaitOverlay`) and MockK's `timeout =` instead.
 *
 */
abstract class AutoTranslateCoordinatorTestBase {

    protected lateinit var scope: CoroutineScope

    /**
     * Stands in for the global `appScope`. It outlives [scope] on purpose: `close()` hands its final
     * write to the global scope precisely because the page's own scope is about to be cancelled.
     */
    protected lateinit var globalScope: CoroutineScope
    protected lateinit var queue: TranslateQueue
    protected lateinit var engine: TranslateEngine
    protected lateinit var modelStore: TranslateModelStore
    protected lateinit var backend: FakeTranslateBackend
    protected lateinit var dbMessageStore: DBMessageStore
    protected lateinit var dbRoomStore: DBRoomStore
    protected lateinit var settingsManager: ConversationSettingsManager
    protected lateinit var hydrator: MessageHydrator
    protected lateinit var coordinator: AutoTranslateCoordinator

    protected val completions = MutableSharedFlow<TranslateQueue.Completion>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    protected val settingUpdates = MutableSharedFlow<ConversationSettingUpdate>(extraBufferCapacity = 8)
    protected val modelState = MutableStateFlow<Map<ModelId, ModelState>>(emptyMap())

    protected val submissions = Collections.synchronizedList(mutableListOf<Submission>())
    protected val overlayEmissions = Collections.synchronizedList(mutableListOf<Map<String, TranslateData>>())

    protected data class Submission(val owner: String, val key: String, val messageId: String, val text: String)

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        val globals = mockk<GlobalHiltEntryPoint>(relaxed = true)
        every { globalServices } returns globals
        every { globals.myId } returns MY_ID
        globalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        every { appScope } returns globalScope
        mockkObject(RoomChangeTracker)

        queue = mockk(relaxed = true)
        every { queue.completions } returns completions
        // The epoch the coordinator captures at construction. A test simulates logout by moving it.
        every { queue.generation } returns 0L
        neverAnsweringSubmit()

        engine = mockk(relaxed = true)
        modelStore = mockk(relaxed = true)
        every { modelStore.state } returns modelState
        every { modelStore.isReady(any<TranslateLang>()) } returns true
        every { modelStore.requiredModels(any()) } returns setOf(ModelId("zh"))
        coEvery { modelStore.awaitReady(any<TranslateLang>()) } returns true
        coEvery { modelStore.awaitReady(any<Set<ModelId>>()) } returns true

        backend = FakeTranslateBackend()
        dbMessageStore = mockk(relaxed = true)
        dbRoomStore = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        every { settingsManager.conversationSettingUpdate } returns settingUpdates
        hydrator = mockk(relaxed = true)
        coEvery { hydrator.hydrate(any()) } answers {
            MessageHydration(firstArg<List<MessageModel>>().associate { it.id to MessageSubData.EMPTY })
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        coordinator = AutoTranslateCoordinator(
            forWhat = For.Account(ROOM),
            scope = scope,
            queue = queue,
            engine = engine,
            modelStore = modelStore,
            backend = backend,
            dbMessageStore = dbMessageStore,
            dbRoomStore = dbRoomStore,
            conversationSettingsManager = settingsManager,
            messageHydrator = hydrator,
        )
        coordinator.overlay.onEach { overlayEmissions += it }.launchIn(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        globalScope.cancel()
        unmockkAll()
    }

    /** Real dispatcher + real clock; see the class comment. */
    protected fun coordinatorTest(body: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { body() }
    }

    /**
     * Starts the coordinator and returns once the stored setting has been applied. `start()` reads
     * it on `Dispatchers.IO`, so a barrier is needed: `ensureDownloaded` (non-zero setting) and
     * `cancelOwner` (any setting) are both inside `applySetting`.
     */
    protected fun startApplied(setting: Int = 1) {
        every { dbRoomStore.getTranslateSetting(ROOM) } returns setting
        coordinator.start()
        if (setting == 0) {
            verify(timeout = BARRIER_MS) { queue.cancelOwner(ROOM) }
        } else {
            verify(timeout = BARRIER_MS) {
                modelStore.ensureDownloaded(any<TranslateLang>(), DownloadTrigger.ENTER_CONVERSATION)
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    protected fun message(id: String, data: TranslateData? = null) = TextChatMessage().apply {
        this.id = id
        isMine = false
        message = "hello $id"
        translateData = data
    }

    /** Window of [count] incoming text messages, hydrated with no child rows. */
    protected fun behaviorOf(count: Int): ChatMessageListBehavior =
        ChatMessageListBehavior(
            messageList = buildMessageSequence(count = count, roomId = ROOM, fromWho = PEER_ID)
        )

    protected suspend fun completeAuto(
        messageId: String,
        target: TranslateLang = TranslateLang.ZH,
        result: TranslateResult = TranslateResult.Translated("译:$messageId", "en"),
    ) = completions.emit(
        TranslateQueue.Completion(
            owner = ROOM,
            key = attemptKey(messageId, target),
            messageId = messageId,
            target = target,
            result = result,
        )
    )

    protected suspend fun completeManual(messageId: String, key: String, result: TranslateResult) =
        completions.emit(
            TranslateQueue.Completion(
                owner = "$ROOM#manual",
                key = key,
                messageId = messageId,
                target = TranslateLang.ZH,
                result = result,
            )
        )

    /** Records the submission and hands back a handle that never completes. */
    protected fun neverAnsweringSubmit() {
        every { queue.submit(any(), any(), any(), any(), any(), any(), any()) } answers {
            submissions += Submission(arg(0), arg(1), arg(2), arg(4))
            CompletableDeferred()
        }
    }

    /** Answers immediately AND broadcasts on `completions`, exactly as the real worker does. */
    protected fun answeringSubmit() {
        every { queue.submit(any(), any(), any(), any(), any(), any(), any()) } answers {
            val owner = arg<String>(0)
            val key = arg<String>(1)
            val messageId = arg<String>(2)
            val target = arg<TranslateLang>(5)
            submissions += Submission(owner, key, messageId, arg(4))
            val result = TranslateResult.Translated("译:$messageId", "en")
            scope.launch {
                completions.emit(TranslateQueue.Completion(owner, key, messageId, target, result))
            }
            CompletableDeferred(result) as Deferred<TranslateResult>
        }
    }

    protected suspend fun awaitOverlay(what: String, predicate: (Map<String, TranslateData>) -> Boolean) =
        awaitCondition(what) { predicate(coordinator.overlay.value) }

    protected suspend fun awaitCondition(what: String, predicate: () -> Boolean) {
        withTimeout(BARRIER_MS) {
            while (!predicate()) delay(POLL_MS)
        }
    }

    companion object {
        const val ROOM = "+10001"
        const val MY_ID = "+19999"
        const val PEER_ID = "+10001"
        const val FIRST_SCREEN_MAX = 10
        const val NEW_INCOMING_BUDGET_MS = 400L
        const val BARRIER_MS = 5_000L
        const val POLL_MS = 10L

        /** Long enough for the 800 ms batch timer, used by the "nothing happens" assertions. */
        const val SETTLE_MS = 1_200L
    }
}
