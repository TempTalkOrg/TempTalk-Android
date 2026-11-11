package com.difft.android.chat.ui

import android.view.View
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.messages.TestScopeApplication
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.VideoProbeState
import org.difft.app.database.models.ContactorModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [28])
class ChatMessageAdapterVideoViewTypeTest {

    private val adapter = object : ChatMessageAdapter(forWhat = null, contactorCache = MessageContactsCacheUtil()) {
        override fun onItemClick(rootView: View, data: ChatMessage) = Unit
        override fun onItemLongClick(rootView: View, data: ChatMessage) = Unit
        override fun onAvatarClicked(contactor: ContactorModel?) = Unit
        override fun onAvatarLongClicked(contactor: ContactorModel?) = Unit
        override fun onQuoteClicked(quote: Quote) = Unit
        override fun onReactionClick(message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) = Unit
        override fun onReactionLongClick(message: ChatMessage, emoji: String) = Unit
        override fun onTranslateRetry(message: TextChatMessage) = Unit
    }

    private fun attachment(
        contentType: String,
        width: Int = 0,
        height: Int = 0,
        videoProbeState: Int = VideoProbeState.NONE,
    ) = Attachment(
        id = "",
        authorityId = 0L,
        contentType = contentType,
        key = null,
        size = 0,
        thumbnail = null,
        digest = null,
        fileName = "clip",
        flags = 0,
        width = width,
        height = height,
        path = null,
        status = 3,
        videoProbeState = videoProbeState,
    )

    private fun viewTypeOf(attachment: Attachment, singleForward: Boolean = false): Int {
        val message = TextChatMessage().apply {
            id = "m-${System.nanoTime()}"
            isMine = false
            if (singleForward) {
                forwardContext = ForwardContext(
                    listOf(Forward(1L, 0, false, "author", null, listOf(attachment), null, null)),
                    isFromGroup = false,
                )
            } else {
                this.attachment = attachment
            }
        }
        
        adapter.submitList(null)
        adapter.submitList(listOf(message))
        return adapter.getItemViewType(0)
    }

    private fun assertBubble(expected: Int, attachment: Attachment) {
        assertEquals(attachment.contentType, expected, viewTypeOf(attachment))
        assertEquals("single forward ${attachment.contentType}", expected, viewTypeOf(attachment, singleForward = true))
    }

    @Test
    fun `T1 sized mp4 is a video bubble`() {
        assertBubble(ChatMessageAdapter.VIEW_TYPE_OTHERS_VIDEO, attachment("video/mp4", 1280, 720))
    }

    @Test
    fun `T28 padded upper-case mp4 is a video bubble`() {
        assertBubble(ChatMessageAdapter.VIEW_TYPE_OTHERS_VIDEO, attachment(" VIDEO/MP4 ", 1280, 720))
    }

    @Test
    fun `T2 unsized quicktime is a file bubble while its probe is pending`() {
        listOf(VideoProbeState.NONE, VideoProbeState.IN_PROGRESS_1, VideoProbeState.IN_PROGRESS_2).forEach {
            assertBubble(ChatMessageAdapter.VIEW_TYPE_OTHERS_ATTACH, attachment("video/quicktime", videoProbeState = it))
        }
    }

    @Test
    fun `T3 failed probe is a file bubble`() {
        assertBubble(ChatMessageAdapter.VIEW_TYPE_OTHERS_ATTACH, attachment("video/quicktime", videoProbeState = VideoProbeState.FAILED))
        assertBubble(
            ChatMessageAdapter.VIEW_TYPE_OTHERS_ATTACH,
            attachment("video/mp4", 640, 360, videoProbeState = VideoProbeState.FAILED)
        )
    }

    @Test
    fun `T4 sized mpeg is a file bubble`() {
        assertBubble(ChatMessageAdapter.VIEW_TYPE_OTHERS_ATTACH, attachment("video/mpeg", 640, 360))
    }

    @Test
    fun `T5 webm is a file bubble`() {
        assertBubble(ChatMessageAdapter.VIEW_TYPE_OTHERS_ATTACH, attachment("video/webm", 640, 360))
    }

    @Test
    fun `images keep the image bubble`() {
        assertBubble(ChatMessageAdapter.VIEW_TYPE_OTHERS_IMAGE, attachment("image/jpeg"))
    }
}
