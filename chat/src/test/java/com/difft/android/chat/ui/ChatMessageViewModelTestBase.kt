package com.difft.android.chat.ui

import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.difft.android.ChatPaginationControllerFactory
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.ChatMessageListBehavior
import com.difft.android.chat.ChatPaginationController
import difft.android.messageserialization.For
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.difft.app.database.WCDB
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.getReadInfoList
import org.difft.app.database.hydration.MessageChildRowLoader
import org.difft.app.database.hydration.MessageHydrator
import org.difft.app.database.isKnownContact
import org.difft.app.database.models.MessageModel
import org.difft.app.database.screenShot
import org.difft.app.database.test.builders.ChildRowCorpus
import org.difft.app.database.test.fakes.FakeMessageChildRowLoader
import org.junit.After
import org.junit.Before
import org.robolectric.Shadows.shadowOf

/**
 * Shared host for cases that drive the REAL [ChatMessageViewModel] pipeline.
 *
 * `ChatMessageViewModel` reads the Hilt-EntryPoint globals `globalServices` / `wcdb` synchronously
 * at construction time, so every case needs the same `mockkStatic` preamble. Extracted here so it
 * has one definition rather than one per test class.
 *
 * The `isKnownContact` stub is what makes non-self `For.Account` conversations usable at all: the
 * production call it replaced built a winq `Expression` inside `initE2eeHintObservers()`, whose
 * static initializer loads a native library the host JVM cannot provide. Because that crash
 * happened inside `viewModelScope.launch(Dispatchers.IO)`, it did not fail its own case — it
 * leaked an uncaught exception into the NEXT `runTest` in the same JVM worker, which is why these
 * suites were `@Ignore`d wholesale.
 */
abstract class ChatMessageViewModelTestBase {

    protected lateinit var wcdb: WCDB
        private set

    /**
     * The `globalServices` entry point every case shares.
     *
     * Exposed because chained stubbing does NOT work here: `every { globalServices.gson } returns …`
     * re-stubs `globalServices` itself to a fresh child mock and silently discards this one (taking
     * the `myId` stub with it). Stub on this reference instead.
     */
    protected lateinit var globalServicesMock: GlobalHiltEntryPoint
        private set

    /** Drives the ViewModel's pagination input; a test writes a behavior to trigger the pipeline. */
    protected lateinit var behaviorFlow: MutableStateFlow<ChatMessageListBehavior?>
        private set

    /**
     * Child rows the injected [MessageHydrator] can see. Assign BEFORE calling [viewModel].
     *
     * Constraint: a corpus used here must not carry `attachment` rows. The hydrator maps those
     * through `AttachmentModel.toAttachment()`, which lives in the `WCDBExtensionsKt` facade this
     * base mocks wholesale, so the call would hit an unstubbed static mock. Attachment-level
     * hydration is covered by the `:database` hydration suites, which mock nothing.
     */
    protected var childRowCorpus: ChildRowCorpus = ChildRowCorpus()

    /** The loader the injected hydrator read through; records the exact `IN` key sets it asked for. */
    protected lateinit var childRowLoader: FakeMessageChildRowLoader
        private set

    /** Hook for observing HOW hydration is dispatched; identity by default. */
    protected var wrapChildRowLoader: (MessageChildRowLoader) -> MessageChildRowLoader = { it }

    private val createdViewModels = mutableListOf<ChatMessageViewModel>()

    @Before
    fun setUpViewModelGlobals() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        globalServicesMock = mockk(relaxed = true)
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns MY_ID
        wcdb = mockk(relaxed = true)
        every { org.difft.app.database.wcdb } returns wcdb
        every { wcdb.getReadInfoList(any()) } returns emptyList()
        every { wcdb.getContactorsFromAllTable(any(), any()) } returns emptyList()
        // Default: the peer IS a contact. Non-friend cases override this per test.
        every { wcdb.isKnownContact(any()) } returns true
        // The eight per-message child-table point queries used to be stubbed here. They are gone
        // from generateMessageTwo entirely — child rows now arrive as MessageSubData from the
        // injected hydrator below. Stubbing WCDBExtensionsKt could not reach that code anyway: the
        // batch loader lives in org.difft.app.database.hydration, a different facade.
        // screenShot() stays: it parses the in-memory screenShotJson column, it is not a DB read.
        every { any<MessageModel>().screenShot() } returns null
        behaviorFlow = MutableStateFlow(null)
    }

    /**
     * Drains every ViewModel this case built BEFORE the static mocks come down.
     *
     * `initE2eeHintObservers()` fires `viewModelScope.launch(Dispatchers.IO) { refreshDbNonFriend() }`
     * at construction. A case that never awaits it — every `isE2eeHintEligible` assertion, for
     * instance — leaves that coroutine in flight; if `unmockkStatic` lands first, it resumes
     * against the REAL `isKnownContact`, hits `UnsatisfiedLinkError` (an Error, so
     * `refreshDbNonFriend`'s `catch (e: Exception)` does not hold it), and the uncaught exception
     * fails the NEXT `runTest` in the same JVM worker rather than this one.
     *
     * Cancelling stops anything not yet started; the pump-and-wait lets whatever IS running
     * complete while the stubs are still installed. The main looper must be pumped rather than
     * blocked on: `viewModelScope` dispatches on `Main.immediate`, and Robolectric's main looper is
     * paused, so a plain `runBlocking { cancelAndJoin() }` from the test thread deadlocks against
     * coroutines that need that looper to finish unwinding.
     */
    @After
    fun tearDownViewModelGlobals() {
        val jobs = createdViewModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        jobs.forEach { it.cancel() }
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        // isCompleted, NOT isActive: a cancelled Job reports isActive=false immediately, while its
        // already-running IO body is still unwinding — waiting on isActive exits at once and the
        // unmockkStatic below lands under the straggler's feet (the exact poisoning this drain exists
        // to prevent). Only isCompleted waits for the body to actually finish.
        while (jobs.any { !it.isCompleted } && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(DRAIN_POLL_MS)
        }
        createdViewModels.clear()
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
        unmockkStatic("org.difft.app.database.WCDBExtensionsKt")
    }

    /**
     * Pumps Robolectric's (PAUSED-by-default) Main looper so Main-dispatched coroutine
     * continuations queued from a background `Dispatchers.Default`/`IO` hop actually run.
     */
    protected fun pumpMainLooper(times: Int = 20, stepMs: Long = 20) {
        repeat(times) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(stepMs)
        }
    }

    /**
     * Builds the ViewModel over a controller whose emissions the test owns via [behaviorFlow].
     *
     * @param initialize false for cases that only read construction-time signals and must not
     *   start the combine pipeline.
     */
    protected fun viewModel(forWhat: For, initialize: Boolean = true): ChatMessageViewModel {
        val controllerMock: ChatPaginationController = mockk(relaxed = true)
        every { controllerMock.chatMessagesStateFlow } returns behaviorFlow.asStateFlow()
        val factory: ChatPaginationControllerFactory = mockk()
        every { factory.create(any()) } returns controllerMock
        // The REAL hydrator over an in-memory loader, not a mock of it: the assertions that matter
        // (which ids reach the `IN` set, on which dispatcher) are about production code, and a
        // mocked hydrator would only replay whatever the test told it.
        childRowLoader = FakeMessageChildRowLoader(childRowCorpus)

        return ChatMessageViewModel(
            forWhat = forWhat,
            jumpMessageTimeStamp = null,
            dbMessageStore = mockk(relaxed = true),
            dbRoomStore = mockk(relaxed = true),
            chatPaginationControllerFactory = factory,
            callManager = mockk(relaxed = true),
            translateManager = mockk(relaxed = true),
            speechToTextManager = mockk(relaxed = true),
            pushReadReceiptSendJobFactory = mockk(relaxed = true),
            activityNoticeDispatcher = mockk(relaxed = true),
            onGoingCallStateManager = mockk(relaxed = true),
            callDataManager = mockk(relaxed = true),
            messageHydrator = MessageHydrator(wrapChildRowLoader(childRowLoader)),
        ).also {
            createdViewModels += it
            if (initialize) it.initialize()
        }
    }

    companion object {
        const val MY_ID = "my-uid"

        // Must comfortably outlast a loaded parallel test JVM: when the drain misses this deadline,
        // unmockkStatic lands before the in-flight IO coroutine resumes, which then hits the real
        // WCDB binding, throws UnsatisfiedLinkError (an Error — not held by the production catch),
        // and poisons the NEXT runTest on the same worker. 2s flaked regularly as the suite grew.
        private const val DRAIN_TIMEOUT_MS = 15_000L
        private const val DRAIN_POLL_MS = 5L
    }
}
