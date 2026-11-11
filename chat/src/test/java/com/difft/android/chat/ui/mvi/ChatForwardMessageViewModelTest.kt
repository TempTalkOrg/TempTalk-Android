package com.difft.android.chat.ui.mvi

import com.difft.android.chat.ui.mvi.ChatForwardMessageContract.Intent
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardContext
import org.junit.Assert.assertEquals
import org.junit.Test


class ChatForwardMessageViewModelTest {

    private val root = ForwardContext(forwards = emptyList(), isFromGroup = false, sharedContactId = "root")
    private val nested1 = ForwardContext(forwards = emptyList(), isFromGroup = false, sharedContactId = "n1")
    private val nested2 = ForwardContext(forwards = emptyList(), isFromGroup = true, sharedContactId = "n2")
    private val outerConversation = For.Group("gid-1")

    @Test
    fun `push grows the stack and trim drops nested levels while keeping the outer source`() {
        val vm = ChatForwardMessageViewModel()

        vm.dispatch(Intent.SetRoot(root, outerConversation, "author-1"))
        vm.dispatch(Intent.PushNested(nested1))
        vm.dispatch(Intent.PushNested(nested2))
        assertEquals(listOf(root, nested1, nested2), vm.state.value.stack)

        vm.dispatch(Intent.TrimTo(2))
        val state = vm.state.value
        assertEquals(listOf(root, nested1), state.stack)
        assertEquals(outerConversation, state.outerSourceConversation)
        assertEquals("author-1", state.outerSourceAuthorId)
    }

    @Test
    fun `set root replaces any previous stack`() {
        val vm = ChatForwardMessageViewModel()
        vm.dispatch(Intent.SetRoot(root, outerConversation, "author-1"))
        vm.dispatch(Intent.PushNested(nested1))

        vm.dispatch(Intent.SetRoot(nested2, null, null))

        assertEquals(listOf(nested2), vm.state.value.stack)
        assertEquals(null, vm.state.value.outerSourceConversation)
    }

    @Test
    fun `replace swaps one level in place and ignores an index outside the stack`() {
        val vm = ChatForwardMessageViewModel()
        vm.dispatch(Intent.SetRoot(root, outerConversation, "author-1"))
        vm.dispatch(Intent.PushNested(nested1))

        vm.dispatch(Intent.ReplaceAt(1, nested2))
        assertEquals(listOf(root, nested2), vm.state.value.stack)

        vm.dispatch(Intent.ReplaceAt(5, nested1))
        assertEquals(listOf(root, nested2), vm.state.value.stack)
        assertEquals(outerConversation, vm.state.value.outerSourceConversation)
    }
}
