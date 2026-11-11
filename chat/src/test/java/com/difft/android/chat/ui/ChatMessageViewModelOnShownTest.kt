package com.difft.android.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.difft.android.chat.message.ConversationUnreadMarker
import difft.android.messageserialization.For
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageViewModelOnShownTest : ChatMessageViewModelTestBase() {

    private val room = For.Account("peer")

    
    private fun clear(vm: ChatMessageViewModel) {
        val store = ViewModelStore()
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            }
        )[ChatMessageViewModel::class.java]
        store.clear()
    }

    @Test
    fun `creation alone neither opens the room nor clears a manual unread mark`() {
        val marker: ConversationUnreadMarker = mockk(relaxed = true)
        val vm = viewModel(room, initialize = false, conversationUnreadMarker = marker)
        pumpMainLooper(times = 3)

        verify(exactly = 0) { marker.onConversationOpened(any()) }
        coVerify(exactly = 0) { marker.clearOnOpen(any()) }

        clear(vm)
        verify(exactly = 0) { marker.onConversationClosed(any()) }
    }

    @Test
    fun `the first onShown opens the room once and onCleared closes it`() {
        val marker: ConversationUnreadMarker = mockk(relaxed = true)
        val vm = viewModel(room, initialize = false, conversationUnreadMarker = marker)

        vm.onShown()
        vm.onShown()
        pumpMainLooper(times = 3)

        verify(exactly = 1) { marker.onConversationOpened(room) }
        coVerify(exactly = 1) { marker.clearOnOpen(room) }

        clear(vm)
        verify(exactly = 1) { marker.onConversationClosed(room) }
    }

    @Test
    fun `a reopen of a shown room clears the unread mark again without opening it twice`() {
        val marker: ConversationUnreadMarker = mockk(relaxed = true)
        val vm = viewModel(room, initialize = false, conversationUnreadMarker = marker)
        vm.onShown()

        vm.onReopened()
        pumpMainLooper(times = 3)

        verify(exactly = 1) { marker.onConversationOpened(room) }
        coVerify(exactly = 2) { marker.clearOnOpen(room) }
        clear(vm)
        verify(exactly = 1) { marker.onConversationClosed(room) }
    }

    @Test
    fun `a reopen before the room was shown leaves it to onShown`() {
        val marker: ConversationUnreadMarker = mockk(relaxed = true)
        val vm = viewModel(room, initialize = false, conversationUnreadMarker = marker)

        vm.onReopened()
        pumpMainLooper(times = 3)

        coVerify(exactly = 0) { marker.clearOnOpen(any()) }
    }
}
