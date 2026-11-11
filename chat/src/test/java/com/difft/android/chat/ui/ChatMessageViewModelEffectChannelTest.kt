package com.difft.android.chat.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.repeatOnLifecycle
import app.cash.turbine.test
import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ForwardNoticeData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config










@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageViewModelEffectChannelTest : ChatMessageViewModelTestBase() {

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun forwardData() = ForwardContextData(
        content = "",
        forwardContexts = listOf(ForwardContext(forwards = emptyList(), isFromGroup = false)),
        scene = ForwardNoticeData.Scene.COMBINED,
        sourceConversation = For.Account("peer"),
        sourceAuthorIds = listOf("peer"),
        messageCount = 2,
    )

    @Test
    fun `a forward effect is delivered once and not replayed to a new collector`() = runTest {
        val vm = viewModel(For.Account(MY_ID), initialize = false)
        val data = forwardData()

        vm.forwardMultiMessage.test {
            vm.sendForwardMultiMessage(data)
            assertEquals(data, awaitItem())
        }
        vm.forwardMultiMessage.test {
            expectNoEvents()
        }
    }

    @Test
    fun `a forward effect sent while no collector is active reaches the next collector once`() = runTest {
        val vm = viewModel(For.Account(MY_ID), initialize = false)
        val data = forwardData()

        vm.sendForwardMultiMessage(data)

        vm.forwardMultiMessage.test {
            assertEquals(data, awaitItem())
        }
        vm.forwardMultiMessage.test {
            expectNoEvents()
        }
    }

    @Test
    fun `two identical forward effects are both delivered`() = runTest {
        val vm = viewModel(For.Account(MY_ID), initialize = false)

        vm.forwardMultiMessage.test {
            vm.sendForwardMultiMessage(forwardData())
            vm.sendForwardMultiMessage(forwardData())
            assertEquals(forwardData(), awaitItem())
            assertEquals(forwardData(), awaitItem())
        }
    }

    @Test
    fun `a confidential view receipt is not replayed to a new collector`() = runTest {
        val vm = viewModel(For.Account(MY_ID), initialize = false)
        val message = TextChatMessage().apply {
            id = "c1"
            authorId = "peer"
            timeStamp = 1L
        }

        vm.confidentialViewReceipt.test {
            vm.sendConfidentialViewReceipt(message)
            assertEquals("c1", awaitItem().id)
        }
        vm.confidentialViewReceipt.test {
            expectNoEvents()
        }
    }

    @Test
    fun `an effect sent from IO is processed exactly once across a stop right after the send`() = runTest {
        val vm = viewModel(For.Account(MY_ID), initialize = false)
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.STARTED
        val received = mutableListOf<ForwardContextData>()
        val collector = launch(Dispatchers.Main) {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.forwardMultiMessage.collect { received += it }
            }
        }

        withContext(Dispatchers.IO) { vm.sendForwardMultiMessage(forwardData()) }
        owner.registry.currentState = Lifecycle.State.CREATED
        owner.registry.currentState = Lifecycle.State.STARTED

        assertEquals(listOf(forwardData()), received)
        collector.cancel()
    }
}
