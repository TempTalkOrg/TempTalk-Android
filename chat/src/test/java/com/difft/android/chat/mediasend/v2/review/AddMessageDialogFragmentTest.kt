package com.difft.android.chat.mediasend.v2.review

import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.difft.android.base.widget.DifftClearableInputView
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.MAX_TEXT_FILE_SIZE
import com.difft.android.chat.mediasend.v2.MediaSelectionViewModel
import com.difft.android.chat.messages.TestScopeApplication
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The media caption on the shared input: the text-file size cap runs on the field's edits, and
 * the confidential style recolours the field's box. Real dialog, real view model; only the toast
 * is stubbed.
 */
@RunWith(RobolectricTestRunner::class)
// API 29: the dialog's keyboard-aware root reads the display through a path Robolectric only backs below 30.
@Config(application = TestScopeApplication::class, sdk = [29])
class AddMessageDialogFragmentTest {

    private lateinit var controller: ActivityController<CaptionHostActivity>

    private fun open(confidentialMode: Int, initialText: String = ""): Pair<CaptionHostActivity, DifftClearableInputView> {
        mockkObject(ToastUtil)
        every { ToastUtil.show(any<String>()) } just Runs
        CaptionHostActivity.confidentialMode = confidentialMode
        controller = Robolectric.buildActivity(CaptionHostActivity::class.java).setup()
        val activity = controller.get()
        // The review screen owns the shared view model before the caption dialog opens.
        ViewModelProvider(activity)[MediaSelectionViewModel::class.java]
        idle()
        AddMessageDialogFragment.show(activity.supportFragmentManager, initialText, false)
        idle()
        val dialog = activity.supportFragmentManager.findFragmentByTag(AddMessageDialogFragment.TAG) as AddMessageDialogFragment
        return activity to dialog.requireView().findViewById(R.id.add_a_message_input)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * The view model's caption once its state update has landed: the store applies updates on a
     * background serial executor and posts the result, so the test waits (bounded) for the
     * expected value instead of idling the main looper once.
     */
    private fun CaptionHostActivity.message(expected: String?): String? {
        val model = ViewModelProvider(this)[MediaSelectionViewModel::class.java]
        val deadline = System.currentTimeMillis() + 2_000
        var seen: String? = null
        while (System.currentTimeMillis() < deadline) {
            idle()
            seen = model.state.value?.message?.toString()
            if (seen == expected) return seen
            Thread.sleep(20)
        }
        return seen
    }

    @After
    fun tearDown() {
        unmockkAll()
        runCatching { controller.destroy() }
    }

    @Test
    fun `an edit within the limit reaches the view model unchanged`() {
        val (activity, input) = open(confidentialMode = 0, initialText = "first")
        assertEquals("first", input.query)
        assertEquals("first", activity.message("first"))
        input.query = "first draft"
        input.onQueryChanged("first draft")
        assertEquals("first draft", activity.message("first draft"))
        verify(exactly = 0) { ToastUtil.show(any<String>()) }
    }

    @Test
    fun `a recreation keeps the typed caption and never reapplies the launch text`() {
        val (activity, input) = open(confidentialMode = 0, initialText = "first")
        input.query = "first draft"
        input.onQueryChanged("first draft")
        assertEquals("first draft", activity.message("first draft"))
        val model = ViewModelProvider(activity)[MediaSelectionViewModel::class.java]
        val seen = mutableListOf<String?>()
        val observer = androidx.lifecycle.Observer<com.difft.android.chat.mediasend.v2.MediaSelectionState> { seen += it.message?.toString() }
        model.state.observeForever(observer)
        seen.clear()
        controller.recreate()
        val recreated = controller.get()
        val dialog = recreated.supportFragmentManager.findFragmentByTag(AddMessageDialogFragment.TAG) as AddMessageDialogFragment
        assertEquals("first draft", dialog.requireView().findViewById<DifftClearableInputView>(R.id.add_a_message_input).query)
        assertEquals("first draft", recreated.message("first draft"))
        model.state.removeObserver(observer)
        assertTrue("first" !in seen, "the launch-time caption must not reach the view model again: $seen")
    }

    @Test
    fun `an edit over the size limit is cut back in the field and the view model`() {
        val (activity, input) = open(confidentialMode = 0)
        val oversized = "a".repeat(MAX_TEXT_FILE_SIZE + 3)
        input.query = oversized
        input.onQueryChanged(oversized)
        val capped = input.query
        assertEquals(MAX_TEXT_FILE_SIZE, capped.toByteArray(Charsets.UTF_8).size)
        verify(exactly = 1) { ToastUtil.show(any<String>()) }
        // Release the large text before the dialog composes it; the view model keeps its copy.
        input.query = ""
        assertEquals(capped, activity.message(capped))
    }

    @Test
    fun `normal mode draws the bordered message box and no field box over it`() {
        val (_, input) = open(confidentialMode = 0)
        assertEquals(R.drawable.chat_msg_input_field_bg, shadowOf(input.background).createdFromResId)
        assertEquals(android.graphics.Color.TRANSPARENT, input.containerColor)
    }

    @Test
    fun `confidential mode draws the confidential bordered box on the confidential area`() {
        val (activity, input) = open(confidentialMode = 1)
        assertEquals(R.drawable.chat_msg_input_bg_confidential, shadowOf(input.background).createdFromResId)
        assertEquals(android.graphics.Color.TRANSPARENT, input.containerColor)
        val area = (input.parent as android.view.View).background as android.graphics.drawable.ColorDrawable
        assertEquals(ContextCompat.getColor(activity, com.difft.android.base.R.color.bg_confidential_area), area.color)
    }
}

class CaptionHostActivity : FragmentActivity() {

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MediaSelectionViewModel(emptyList(), mockk(relaxed = true), initialConfidentialMode = confidentialMode) as T
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.difft.android.base.R.style.Theme_Chative)
        super.onCreate(savedInstanceState)
    }

    companion object {
        /** Robolectric constructs the activity, so the mode is handed over statically. */
        var confidentialMode = 0
    }
}
