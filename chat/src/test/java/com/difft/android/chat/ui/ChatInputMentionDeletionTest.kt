package com.difft.android.chat.ui

import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.chat.R
import com.difft.android.chat.common.MAX_TEXT_FILE_SIZE
import com.difft.android.chat.databinding.ChatFragmentInputBinding
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.widget.UnifiedInputTouchHandler
import com.difft.android.chat.widget.VoiceRecorderView
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Mention
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.difft.app.database.models.ContactorModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Exercises the real composer layout and initView listeners, with persistence isolated. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ChatInputMentionDeletionTest {
    private lateinit var fragment: ChatMessageInputFragment
    private lateinit var binding: ChatFragmentInputBinding
    private lateinit var lifecycle: LifecycleRegistry
    private lateinit var chatViewModel: ChatMessageViewModel
    private lateinit var draftViewModel: DraftViewModel

    @Before
    fun setUp() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(), androidx.appcompat.R.style.Theme_AppCompat_Light
        )
        binding = ChatFragmentInputBinding.inflate(LayoutInflater.from(context))
        fragment = spyk(ChatMessageInputFragment(), recordPrivateCalls = true)
        val owner = mockk<LifecycleOwner>()
        lifecycle = LifecycleRegistry.createUnsafe(owner)
        every { owner.lifecycle } returns lifecycle
        lifecycle.currentState = Lifecycle.State.STARTED
        every { fragment.viewLifecycleOwner } returns owner
        every { fragment.requireContext() } returns context
        every { fragment.resources } returns context.resources
        justRun { fragment["updateConfidential"]() }
        chatViewModel = mockk(relaxed = true)
        draftViewModel = mockk(relaxed = true)
        every { chatViewModel.forWhat } returns For.Group("test-group")
        setField("binding", binding)
        setField("isGroup", true)
        setField("chatViewModel\$delegate", lazy { chatViewModel })
        setField("draftViewModel\$delegate", lazy { draftViewModel })
        ChatMessageInputFragment::class.java.getDeclaredMethod("initView").apply {
            isAccessible = true
        }.invoke(fragment)
    }

    @After
    fun tearDown() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
        unmockkAll()
    }

    @Test
    fun `deleting a mention hides the send column`() {
        binding.edittextInput.setText("@Alice ")
        setField("mentions", mutableListOf(Mention(0, 6, "alice", 0)))
        assertEquals(View.VISIBLE, binding.buttonSubmit.visibility)

        binding.edittextInput.text!!.delete(5, 6)

        assertEquals("", binding.edittextInput.text.toString().trim())
        assertEquals(View.GONE, binding.buttonSubmit.visibility)
        assertEquals(View.GONE, binding.clRightActions.visibility)
        verify {
            draftViewModel.updateDraft("test-group", match {
                it.content == binding.edittextInput.text.toString() && it.mentions.isEmpty()
            })
        }
    }

    @Test
    fun `deleting a mention preserves the following message`() {
        binding.edittextInput.setText("@Alice hello")
        setField("mentions", mutableListOf(Mention(0, 6, "alice", 0)))

        binding.edittextInput.text!!.delete(5, 6)

        assertEquals("hello", binding.edittextInput.text.toString().trim())
        assertEquals(View.VISIBLE, binding.buttonSubmit.visibility)
        assertEquals(View.VISIBLE, binding.clRightActions.visibility)
    }

    @Test
    fun `typing after clearing a mention keeps the new text`() {
        binding.edittextInput.setText("@Alice ")
        setField("mentions", mutableListOf(Mention(0, 6, "alice", 0)))
        binding.edittextInput.text!!.clear()
        assertEquals(View.GONE, binding.clRightActions.visibility)

        binding.edittextInput.text!!.append("h")

        assertEquals("h", binding.edittextInput.text.toString())
        assertEquals(View.VISIBLE, binding.buttonSubmit.visibility)
        assertEquals(View.VISIBLE, binding.clRightActions.visibility)
    }

    @Test
    fun `backspace inside a mention hides the send column`() {
        binding.edittextInput.setText("@Alice ")
        setField("mentions", mutableListOf(Mention(0, 6, "alice", 0)))
        binding.edittextInput.setSelection(6)

        binding.edittextInput.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        binding.edittextInput.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))

        assertEquals("", binding.edittextInput.text.toString().trim())
        assertEquals(View.GONE, binding.buttonSubmit.visibility)
        assertEquals(View.GONE, binding.clRightActions.visibility)
    }

    @Test
    fun `a whitespace-only multiline draft keeps the collapsed confidential toggle reachable`() {
        setField("isConfidentialCollapsed", true)
        setField("shouldHideConfidential", false)

        binding.edittextInput.setText("\n\n\n\n")

        assertEquals(View.GONE, binding.buttonSubmit.visibility)
        assertEquals(View.VISIBLE, binding.clRightActions.visibility)
    }

    @Test
    fun `the right column has no voice or keyboard toggle`() {
        val ids = (0 until binding.clRightButtons.childCount).map { binding.clRightButtons.getChildAt(it).id }

        assertEquals(listOf(binding.buttonSubmit.id), ids)
    }

    @Test
    fun `ordinary friend chat text toggles the send column`() {
        setField("isGroup", false)
        setField("isFriend", true)

        binding.edittextInput.setText("hello")
        assertEquals(View.VISIBLE, binding.buttonSubmit.visibility)
        assertEquals(View.VISIBLE, binding.clRightActions.visibility)

        binding.edittextInput.text!!.clear()
        assertEquals(View.GONE, binding.buttonSubmit.visibility)
        assertEquals(View.GONE, binding.clRightActions.visibility)
    }

    @Test
    fun `truncation saves the final buffer and leaves a valid cursor`() {
        setField("isGroup", false)
        setField("isFriend", true)

        binding.edittextInput.setText("a".repeat(MAX_TEXT_FILE_SIZE + 1))

        assertEquals(MAX_TEXT_FILE_SIZE, binding.edittextInput.length())
        assertEquals(MAX_TEXT_FILE_SIZE, binding.edittextInput.selectionStart)
        assertEquals(View.VISIBLE, binding.buttonSubmit.visibility)
        verify {
            draftViewModel.updateDraft("test-group", match {
                it.content?.length == MAX_TEXT_FILE_SIZE
            })
        }
    }

    @Test
    fun `a group edit with mentions saves one draft with the final text`() {
        binding.edittextInput.setText("@Alice ")
        setField("mentions", mutableListOf(Mention(0, 6, "alice", 0)))
        clearMocks(draftViewModel)

        binding.edittextInput.text!!.append("hi")

        verify(exactly = 1) { draftViewModel.updateDraft(any(), any()) }
        verify { draftViewModel.updateDraft("test-group", match { it.content == "@Alice hi" }) }
    }

    @Test
    fun `group truncation drops a mention cut off by the size limit`() {
        val alice = ContactorModel().apply { id = "alice"; name = "Alice" }
        val stale = Mention(0, 1, "stale", 0)
        val mentions = mutableListOf(stale)
        setField("mentions", mentions)
        setField("mentionsSelectedContacts", hashSetOf(alice))
        every { fragment.isAdded } returns true
        every { fragment.view } returns binding.root

        binding.edittextInput.setText("a".repeat(MAX_TEXT_FILE_SIZE) + "@Alice")

        assertEquals(MAX_TEXT_FILE_SIZE, binding.edittextInput.length())
        val deadline = System.currentTimeMillis() + 10_000
        while (mentions == listOf(stale) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
        }
        assertEquals(emptyList<Mention>(), mentions)
    }

    @Test
    fun `outside text input a group chat shows the hold-to-talk hint`() {
        every { fragment.view } returns binding.root
        setField("shouldHideConfidential", true)

        invokePrivate("refreshInputHint")

        val expected = binding.root.context.getString(R.string.chat_message_input_hint_hold_to_talk)
        assertTrue(binding.edittextInput.hint.toString().startsWith(expected))
    }

    @Test
    fun `the cursor is hidden only while the hold-to-talk hint shows`() {
        every { fragment.view } returns binding.root
        setField("shouldHideConfidential", true)

        invokePrivate("refreshInputHint")
        assertFalse(binding.edittextInput.isCursorVisible)

        binding.edittextInput.setText("h")
        assertTrue(binding.edittextInput.isCursorVisible)
    }

    @Test
    fun `a non-friend chat keeps the regular hint`() {
        every { fragment.view } returns binding.root
        every { chatViewModel.neutralInputHintRes } returns R.string.chat_message_input_hint
        setField("isGroup", false)
        setField("isFriend", false)
        setField("shouldHideConfidential", true)

        invokePrivate("refreshInputHint")

        assertEquals(binding.root.context.getString(R.string.chat_message_input_hint), binding.edittextInput.hint.toString())
    }

    @Test
    fun `a quick tap does not start recording`() {
        val (host, recorder) = installRecorderHost(beginResult = true)

        touch(MotionEvent.ACTION_DOWN)
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS - 100)
        touch(MotionEvent.ACTION_UP)
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS)

        verify(exactly = 0) { recorder.beginHold(any()) }
        verify(exactly = 0) { host.setVoiceRecordOverlayVisible(true) }
    }

    @Test
    fun `holding past the threshold records over the input field`() {
        val (host, recorder) = installRecorderHost(beginResult = true)

        touch(MotionEvent.ACTION_DOWN)
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS)

        verify { host.setVoiceRecordOverlayVisible(true) }
        verify { recorder.beginHold(binding.clInputContent) }
        assertEquals(0.4f, binding.clLeftActions.alpha)

        touch(MotionEvent.ACTION_MOVE, y = -300f)
        touch(MotionEvent.ACTION_UP, y = -300f)

        verify { recorder.moveHold(any(), any()) }
        verify { recorder.endHold() }
    }

    @Test
    fun `a rejected hold hides the overlay and leaves the actions undimmed`() {
        val (host, _) = installRecorderHost(beginResult = false)

        touch(MotionEvent.ACTION_DOWN)
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS)

        verify { host.setVoiceRecordOverlayVisible(false) }
        assertEquals(1f, binding.clLeftActions.alpha)
    }

    @Test
    fun `a non-friend chat never records on hold`() {
        setField("isGroup", false)
        setField("isFriend", false)
        val (_, recorder) = installRecorderHost(beginResult = true)

        touch(MotionEvent.ACTION_DOWN)
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS)

        verify(exactly = 0) { recorder.beginHold(any()) }
        assertFalse(binding.clInputContent.scaleX < 1f)
    }

    private fun installRecorderHost(beginResult: Boolean): Pair<VoiceRecordHost, VoiceRecorderView> {
        val recorder = mockk<VoiceRecorderView>(relaxed = true)
        every { recorder.beginHold(any()) } returns beginResult
        val host = mockk<VoiceRecordHost>(relaxed = true)
        every { host.voiceRecorderView } returns recorder
        setField("voiceRecordHost", host)
        every { fragment.isAdded } returns true
        every { fragment.view } returns binding.root
        return host to recorder
    }

    private fun touch(action: Int, y: Float = 10f) {
        val event = MotionEvent.obtain(0L, 0L, action, 10f, y, 0)
        binding.edittextInput.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun idle(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    private fun invokePrivate(name: String) {
        ChatMessageInputFragment::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
        }.invoke(fragment)
    }

    private fun setField(name: String, value: Any) {
        ChatMessageInputFragment::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(fragment, value)
    }
}
