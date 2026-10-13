package com.difft.android.chat.ui.messageaction

import com.difft.android.chat.message.TextChatMessage
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D1 (#1294): [dispatchTo] routes each [MessageAction.Type] to exactly one listener method.
 * The table must cover every enum entry, so adding a type without a row fails here.
 */
class MessageActionDispatchTest {

    private val listener = mockk<MessageActionCoordinator.ActionListener>(relaxed = true)
    private val message = mockk<TextChatMessage>()

    /** Expected call per type; null means the type is handled elsewhere and must not call the listener. */
    private val expected: Map<MessageAction.Type, (() -> Unit)?> = mapOf(
        MessageAction.Type.QUOTE to { listener.onQuote(message) },
        MessageAction.Type.COPY to { listener.onCopy(message, null) },
        MessageAction.Type.TRANSLATE to { listener.onTranslate(message, null) },
        MessageAction.Type.TRANSLATE_OFF to { listener.onTranslateOff(message) },
        MessageAction.Type.FORWARD to { listener.onForward(message, null) },
        MessageAction.Type.SPEECH_TO_TEXT to { listener.onSpeechToText(message) },
        MessageAction.Type.SPEECH_TO_TEXT_OFF to { listener.onSpeechToTextOff(message) },
        MessageAction.Type.SAVE to { listener.onSave(message) },
        MessageAction.Type.MULTISELECT to { listener.onMultiSelect(message) },
        MessageAction.Type.SAVE_TO_NOTE to { listener.onSaveToNote(message) },
        MessageAction.Type.DELETE_SAVED to { listener.onDeleteSaved(message) },
        MessageAction.Type.RECALL to { listener.onRecall(message) },
        MessageAction.Type.MORE_INFO to { listener.onMoreInfo(message) },
        MessageAction.Type.FAVORITE_GIF to { listener.onFavoriteGif(message) },
        MessageAction.Type.SELECT_ALL to null,
        MessageAction.Type.RESEND to null,
        MessageAction.Type.DELETE to null,
    )

    @Test
    fun `D1 every action type dispatches to exactly its listener method`() {
        assertEquals(MessageAction.Type.entries.toSet(), expected.keys)
        assertEquals(MessageAction.Type.entries.size, expected.size)

        MessageAction.Type.entries.forEach { type ->
            clearMocks(listener)

            type.dispatchTo(listener, message)

            expected.getValue(type)?.let { call -> verify(exactly = 1) { call() } }
            confirmVerified(listener)
        }
    }
}
