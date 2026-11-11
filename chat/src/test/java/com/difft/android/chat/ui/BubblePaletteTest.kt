package com.difft.android.chat.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.R
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.widget.fileTypeIcon
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config





@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class BubblePaletteTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private fun textRow(sent: Boolean): ViewGroup {
        val inflater = LayoutInflater.from(context)
        val layout = if (sent) R.layout.chat_item_chat_message_list_text_mine else R.layout.chat_item_chat_message_list_text_others
        val row = inflater.inflate(layout, null)
        row.findViewById<ChatMessageContainerView>(R.id.contentContainer).sent = sent
        val frame = row.findViewById<ViewGroup>(R.id.contentFrame)
        inflater.inflate(R.layout.chat_item_content_text, frame, true)
        return frame
    }

    private fun bindText(frame: ViewGroup, isMine: Boolean): TextView {
        val message = TextChatMessage().apply {
            id = "m1"
            this.isMine = isMine
            message = "hello https://example.com"
        }
        TextContentBinder.bind(frame, message, mockk<MessageContactsCacheUtil>(relaxed = true), false, 0)
        return frame.findViewById(R.id.textView)
    }

    @Test
    fun `a forwarded message on the user's bubble is drawn in the sent colours`() {
        val text = bindText(textRow(sent = true), isMine = false)
        assertEquals(color(com.difft.android.base.R.color.bubble_sent_text), text.currentTextColor)
        assertEquals(color(com.difft.android.base.R.color.bubble_sent_link), text.linkTextColors.defaultColor)
    }

    @Test
    fun `a received bubble keeps the primary text and info links`() {
        val text = bindText(textRow(sent = false), isMine = false)
        assertEquals(color(com.difft.android.base.R.color.t_primary), text.currentTextColor)
        assertEquals(color(com.difft.android.base.R.color.t_info), text.linkTextColors.defaultColor)
    }

    @Test
    fun `outside a message row the palette is the received one`() {
        val palette = TextView(context).bubblePalette()
        assertEquals(color(com.difft.android.base.R.color.t_primary), palette.text)
    }

    @Test
    fun `file types map to their glyph and everything else to the plain file`() {
        assertEquals(R.drawable.chat_ic_file_type_doc, fileTypeIcon("Spec.DOCX"))
        assertEquals(R.drawable.chat_ic_file_type_doc, fileTypeIcon("notes.pages"))
        assertEquals(R.drawable.chat_ic_file_type_xls, fileTypeIcon("budget.numbers"))
        assertEquals(R.drawable.chat_ic_file_type_ppt, fileTypeIcon("deck.key"))
        assertEquals(R.drawable.chat_ic_file_type_pdf, fileTypeIcon("a.pdf"))
        assertEquals(R.drawable.chat_ic_file_type_zip, fileTypeIcon("logs.tar.gz"))
        assertEquals(R.drawable.chat_ic_file_type_file, fileTypeIcon("readme.txt"))
        assertEquals(R.drawable.chat_ic_file_type_file, fileTypeIcon("Makefile"))
        assertEquals(R.drawable.chat_ic_file_type_file, fileTypeIcon(null))
    }
}
