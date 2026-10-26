package com.difft.android.chat.ui

import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.chat.common.MAX_TEXT_FILE_SIZE
import com.difft.android.chat.databinding.ChatFragmentInputBinding
import com.difft.android.chat.messages.TestScopeApplication
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
    fun `deleting a mention restores the voice input button`() {
        binding.edittextInput.setText("@Alice ")
        setField("mentions", mutableListOf(Mention(0, 6, "alice", 0)))
        assertEquals(View.VISIBLE, binding.buttonSubmit.visibility)

        binding.edittextInput.text!!.delete(5, 6)

        assertEquals("", binding.edittextInput.text.toString().trim())
        assertEquals(View.GONE, binding.buttonSubmit.visibility)
        assertEquals(View.VISIBLE, binding.buttonVoice.visibility)
        assertEquals(View.GONE, binding.buttonKeyboard.visibility)
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
        assertEquals(View.GONE, binding.buttonVoice.visibility)
    }

    @Test
    fun `typing after clearing a mention keeps the new text`() {
        binding.edittextInput.setText("@Alice ")
        setField("mentions", mutableListOf(Mention(0, 6, "alice", 0)))
        binding.edittextInput.text!!.clear()
        assertEquals(View.VISIBLE, binding.buttonVoice.visibility)

        binding.edittextInput.text!!.append("h")

        assertEquals("h", binding.edittextInput.text.toString())
        assertEquals(View.VISIBLE, binding.buttonSubmit.visibility)
        assertEquals(View.GONE, binding.buttonVoice.visibility)
    }

    @Test
    fun `backspace inside a mention restores the input mode button`() {
        binding.edittextInput.setText("@Alice ")
        setField("mentions", mutableListOf(Mention(0, 6, "alice", 0)))
        binding.edittextInput.setSelection(6)

        binding.edittextInput.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        binding.edittextInput.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))

        assertEquals("", binding.edittextInput.text.toString().trim())
        assertEquals(View.GONE, binding.buttonSubmit.visibility)
        assertEquals(View.VISIBLE, binding.buttonVoice.visibility)
    }

    @Test
    fun `the restored button can switch to voice and back to keyboard`() {
        binding.edittextInput.setText("@Alice ")
        setField("mentions", mutableListOf(Mention(0, 6, "alice", 0)))
        binding.edittextInput.text!!.clear()
        every { fragment.isAdded } returns true
        every { fragment.view } returns binding.root

        assertTrue(binding.buttonVoice.performClick())
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)

        verify { chatViewModel.setVoiceVisibility(true) }
        assertEquals(View.VISIBLE, binding.buttonKeyboard.visibility)
        assertEquals(View.GONE, binding.buttonVoice.visibility)
        assertEquals(View.GONE, binding.buttonSubmit.visibility)

        assertTrue(binding.buttonKeyboard.performClick())

        verify { chatViewModel.setVoiceVisibility(false) }
        assertEquals(View.VISIBLE, binding.buttonVoice.visibility)
        assertEquals(View.GONE, binding.buttonKeyboard.visibility)
    }

    @Test
    fun `ordinary friend chat text still toggles send and voice buttons`() {
        setField("isGroup", false)
        setField("isFriend", true)

        binding.edittextInput.setText("hello")
        assertEquals(View.VISIBLE, binding.buttonSubmit.visibility)
        assertEquals(View.GONE, binding.buttonVoice.visibility)

        binding.edittextInput.text!!.clear()
        assertEquals(View.GONE, binding.buttonSubmit.visibility)
        assertEquals(View.VISIBLE, binding.buttonVoice.visibility)
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

    private fun setField(name: String, value: Any) {
        ChatMessageInputFragment::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(fragment, value)
    }
}
