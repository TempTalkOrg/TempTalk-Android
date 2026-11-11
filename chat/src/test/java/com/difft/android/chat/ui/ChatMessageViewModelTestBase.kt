package com.difft.android.chat.ui

import com.difft.android.chat.message.ConversationUnreadMarker
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.difft.android.ChatPaginationControllerFactory
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.ChatMessageListBehavior
import com.difft.android.chat.ChatPaginationController
import difft.android.messageserialization.For
import com.difft.android.chat.translate.AutoTranslateCoordinator
import com.difft.android.chat.translate.AutoTranslateCoordinatorFactory
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







abstract class ChatMessageViewModelTestBase {

    protected lateinit var wcdb: WCDB
        private set

    






    protected lateinit var globalServicesMock: GlobalHiltEntryPoint
        private set

    
    protected lateinit var behaviorFlow: MutableStateFlow<ChatMessageListBehavior?>
        private set

    







    protected var childRowCorpus: ChildRowCorpus = ChildRowCorpus()

    
    protected lateinit var childRowLoader: FakeMessageChildRowLoader
        private set

    
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
        
        every { wcdb.isKnownContact(any()) } returns true
        
        every { any<MessageModel>().screenShot() } returns null
        behaviorFlow = MutableStateFlow(null)
    }

    




    @After
    fun tearDownViewModelGlobals() {
        val jobs = createdViewModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        jobs.forEach { it.cancel() }
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        
        
        
        
        while (jobs.any { !it.isCompleted } && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(DRAIN_POLL_MS)
        }
        createdViewModels.clear()
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
        unmockkStatic("org.difft.app.database.WCDBExtensionsKt")
    }

    



    protected fun pumpMainLooper(times: Int = 20, stepMs: Long = 20) {
        repeat(times) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(stepMs)
        }
    }

    





    protected fun viewModel(
        forWhat: For,
        initialize: Boolean = true,
        conversationUnreadMarker: ConversationUnreadMarker = mockk(relaxed = true),
    ): ChatMessageViewModel {
        val controllerMock: ChatPaginationController = mockk(relaxed = true)
        every { controllerMock.chatMessagesStateFlow } returns behaviorFlow.asStateFlow()
        val factory: ChatPaginationControllerFactory = mockk()
        every { factory.create(any()) } returns controllerMock
        
        
        
        childRowLoader = FakeMessageChildRowLoader(childRowCorpus)
        
        
        val translateCoordinator: AutoTranslateCoordinator = mockk(relaxed = true)
        every { translateCoordinator.overlay } returns MutableStateFlow(emptyMap())
        val translateFactory: AutoTranslateCoordinatorFactory = mockk()
        every { translateFactory.create(any(), any()) } returns translateCoordinator

        return ChatMessageViewModel(
            forWhat = forWhat,
            jumpMessageTimeStamp = null,
            dbMessageStore = mockk(relaxed = true),
            dbRoomStore = mockk(relaxed = true),
            chatPaginationControllerFactory = factory,
            callManager = mockk(relaxed = true),
            autoTranslateCoordinatorFactory = translateFactory,
            speechToTextManager = mockk(relaxed = true),
            readReceiptDispatcher = mockk(relaxed = true),
            activityNoticeDispatcher = mockk(relaxed = true),
            onGoingCallStateManager = mockk(relaxed = true),
            callDataManager = mockk(relaxed = true),
            messageHydrator = MessageHydrator(wrapChildRowLoader(childRowLoader)),
            conversationUnreadMarker = conversationUnreadMarker,
        ).also {
            createdViewModels += it
            if (initialize) it.initialize()
        }
    }

    companion object {
        const val MY_ID = "my-uid"

        
        private const val DRAIN_TIMEOUT_MS = 15_000L
        private const val DRAIN_POLL_MS = 5L
    }
}
