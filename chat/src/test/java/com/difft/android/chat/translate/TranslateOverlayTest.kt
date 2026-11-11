package com.difft.android.chat.translate

import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.NotifyChatMessage
import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test






class TranslateOverlayTest {

    private fun message(id: String, data: TranslateData?) = TextChatMessage().apply {
        this.id = id
        translateData = data
    }


    @Test
    fun `T55a an id present in the overlay overrides the hydrated value`() {
        val hydrated = TranslateData(TranslateStatus.Invisible, null, null)
        val fresh = TranslateData(TranslateStatus.ShowCN, "你好", null)
        val messages = listOf<ChatMessage>(message("m1", hydrated))

        applyTranslateOverlay(messages, mapOf("m1" to fresh))

        assertSame(fresh, (messages[0] as TextChatMessage).translateData)
    }

    @Test
    fun `T55b an id absent from the overlay keeps the hydrated value`() {
        val hydrated = TranslateData(TranslateStatus.ShowEN, null, "hi")
        val messages = listOf<ChatMessage>(message("m1", hydrated))

        applyTranslateOverlay(messages, mapOf("other" to TranslateData(TranslateStatus.ShowCN, "x", null)))

        assertSame(hydrated, (messages[0] as TextChatMessage).translateData)
    }

    @Test
    fun `an empty overlay is a no-op`() {
        val hydrated = TranslateData(TranslateStatus.ShowEN, null, "hi")
        val messages = listOf<ChatMessage>(message("m1", hydrated))

        applyTranslateOverlay(messages, emptyMap())

        assertSame(hydrated, (messages[0] as TextChatMessage).translateData)
    }

    @Test
    fun `non-text messages are skipped`() {
        val notify = NotifyChatMessage().apply { id = "m1" }
        val messages = listOf<ChatMessage>(notify)

        
        applyTranslateOverlay(messages, mapOf("m1" to TranslateData(TranslateStatus.ShowCN, "x", null)))
    }


    @Test
    fun `T56 the shared previous value is never mutated in place`() {
        val shared = TranslateData(TranslateStatus.Invisible, null, null)
        val keepsOldValue = message("m-old", shared)
        val getsNewValue = message("m-new", shared)
        val fresh = shared.copy(translateStatus = TranslateStatus.ShowCN, translatedContentCN = "你好")

        applyTranslateOverlay(listOf(getsNewValue), mapOf("m-new" to fresh))

        
        
        assertSame(shared, keepsOldValue.translateData)
        assertEquals(TranslateStatus.Invisible, keepsOldValue.translateData?.translateStatus)
        assertNotSame(shared, getsNewValue.translateData)
        assertEquals(TranslateStatus.ShowCN, getsNewValue.translateData?.translateStatus)
    }
}
