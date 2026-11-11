package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.compose.input.ClearMode
import com.difft.android.base.ui.compose.input.DifftInputSurface
import com.difft.android.base.ui.compose.input.DifftTextFieldStyle
import com.difft.android.base.widget.DifftClearableInputView
import com.difft.android.chat.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class FormInputLayoutMigrationTest {

    private fun inflate(layoutId: Int): View {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themed = android.view.ContextThemeWrapper(context, com.difft.android.base.R.style.Theme_Chative)
        return LayoutInflater.from(themed).inflate(layoutId, null, false)
    }

    private fun View.editTexts(): List<EditText> = when (this) {
        is EditText -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).editTexts() }
        else -> emptyList()
    }

    @Test
    fun forwardDialogNote_isThePopupSearchShell() {
        val root = inflate(R.layout.chat_layout_forward_dialog)
        val note = root.findViewById<View>(R.id.et_message)
        assertTrue(note is DifftClearableInputView, "et_message is ${note?.javaClass?.name}")
        assertEquals(DifftTextFieldStyle.Search, note.inputStyle)
        assertEquals(DifftInputSurface.Popup, note.surface)
        assertTrue(root.editTexts().isEmpty(), "raw EditText left in chat_layout_forward_dialog")
    }

    @Test
    fun mediaCaption_isAFiveLineTextArea() {
        val root = inflate(R.layout.v2_media_add_message_dialog_fragment_content)
        val caption = root.findViewById<View>(R.id.add_a_message_input)
        assertTrue(caption is DifftClearableInputView, "add_a_message_input is ${caption?.javaClass?.name}")
        assertEquals(5, caption.maxLines)
        assertEquals(ClearMode.Never, caption.clearMode)
        assertTrue(root.editTexts().isEmpty(), "raw EditText left in the caption layout")
    }

    
    @Test
    fun forwardNote_readsTheShellQueryTrimmed() {
        val note = inflate(R.layout.chat_layout_forward_dialog).findViewById<DifftClearableInputView>(R.id.et_message)
        note.query = "  see you  "
        assertEquals("see you", forwardNoteText(note))
        assertEquals("", forwardNoteText(null))
    }
}
