package com.difft.android.chat.ui

import com.difft.android.chat.R
import difft.android.messageserialization.For
import org.difft.app.database.cache.OfficialAccountCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatMessageViewModelNeutralInputHintTest : ChatMessageViewModelTestBase() {

    @After
    fun resetOfficialAccountCache() {
        OfficialAccountCache.put(OFFICIAL_ID, false)
    }

    
    @Test
    fun `T3-1 neutralInputHintRes returns e2ee hint for group`() {
        assertEquals(
            R.string.chat_message_input_hint_e2ee,
            viewModel(For.Group("g1"), initialize = false).neutralInputHintRes
        )
    }

    
    @Test
    fun `T3-2 neutralInputHintRes returns e2ee hint for non-self non-official 1v1`() {
        assertEquals(
            R.string.chat_message_input_hint_e2ee,
            viewModel(For.Account("stranger-uid"), initialize = false).neutralInputHintRes
        )
    }

    
    @Test
    fun `T3-3 neutralInputHintRes returns existing generic hint for self account`() {
        assertEquals(
            R.string.chat_message_input_hint,
            viewModel(For.Account(MY_ID), initialize = false).neutralInputHintRes
        )
    }

    
    @Test
    fun `T3-4 neutralInputHintRes returns existing generic hint for official account`() {
        OfficialAccountCache.put(OFFICIAL_ID, true)
        assertEquals(
            R.string.chat_message_input_hint,
            viewModel(For.Account(OFFICIAL_ID), initialize = false).neutralInputHintRes
        )
    }

    
    @Test
    fun `T3-13 chat_message_input_hint_e2ee resolves and chat_message_input_hint is unchanged (en)`() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals("End-to-end encrypted", app.getString(R.string.chat_message_input_hint_e2ee))
        assertEquals("Message", app.getString(R.string.chat_message_input_hint))
    }

    
    @Config(sdk = [28], qualifiers = "zh")
    @Test
    fun `T3-13 chat_message_input_hint_e2ee resolves and chat_message_input_hint is unchanged (zh)`() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals("已端到端加密", app.getString(R.string.chat_message_input_hint_e2ee))
        assertEquals("消息", app.getString(R.string.chat_message_input_hint))
    }

    private companion object {
        const val OFFICIAL_ID = "official-uid"
    }
}
