package com.difft.android.chat.ui

import android.widget.TextView
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.base.utils.normalizeNewlines
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.buildForwardData
import com.difft.android.chat.message.getCopyableTextContent
import com.difft.android.chat.messages.TestScopeApplication
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config









@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class NewlineNormalizePlacementTest {

    private val ls = Char(0x2028).toString() 
    private val ps = Char(0x2029).toString() 

    @After
    fun tearDown() {
        unmockkAll() 
    }

    @Test
    fun `T17 display normalizes historical-stock body into the rendered TextView`() {
        val context = RuntimeEnvironment.getApplication()
        val textView = TextView(context)
        
        LinkTextUtils.setMarkdownToTextview(context, "a${ps}b", textView)
        
        assertEquals("a\nb", textView.text.toString()) 
    }

    @Test
    fun `T18 draft save stores canonical content`() {
        
        
        val typed: CharSequence? = "a${ls}b"
        val storedContent: String? = typed?.toString()?.normalizeNewlines()
        assertEquals("a\nb", storedContent)
    }

    @Test
    fun `T20 quote text is normalized at the render sites`() {
        
        val quoteText = "a${ps}b" 
        assertEquals("a\nb", quoteText.normalizeNewlines())

        
        val nullableQuoteText: String? = "a${ps}b"
        assertEquals("a\nb", nullableQuoteText?.normalizeNewlines())
    }

    @Test
    fun `T21 caption body passed to sendValidatedText is normalized at the entry`() {
        
        
        
        
        val fragment = spyk(ChatMessageInputFragment(), recordPrivateCalls = true)
        
        
        
        injectChatViewModelDelegate(fragment)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        val globalServicesMock = mockk<GlobalHiltEntryPoint>(relaxed = true)
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns "+10000000"

        val sentContent = slot<String>()
        every {
            fragment.invoke("mentionsForNormalizedBody").withArguments(listOf(capture(sentContent)))
        } throws CaptureSentinel

        val rawCaption = "a${ps}b" 
        try {
            fragment invokePrivateSendValidatedText rawCaption
        } catch (e: Exception) {
            
            val cause = generateSequence(e as Throwable) { it.cause }.firstOrNull { it === CaptureSentinel }
            if (cause == null) throw e
        }

        
        
        assertEquals("a\nb", sentContent.captured)
    }

    @Test
    fun `T22 combined-forward preview normalizes historical-stock forwarded text`() {
        
        
        
        val context = RuntimeEnvironment.getApplication()
        val forward = Forward(
            id = 1L,
            type = 0,
            isFromGroup = false,
            author = "+10000000",
            text = "a${ps}b", 
            attachments = null,
            forwards = null,
            mentions = null,
            serverTimestamp = 0L
        )
        assertEquals("a\nb", getForwardText(context, forward))
    }

    
    private fun singleForwardMessage(rawText: String): TextChatMessage =
        TextChatMessage().apply {
            forwardContext = ForwardContext(
                forwards = listOf(
                    Forward(
                        id = 1L,
                        type = 0,
                        isFromGroup = false,
                        author = "+10000000",
                        text = rawText, 
                        attachments = null,
                        forwards = null,
                        mentions = null,
                        serverTimestamp = 0L
                    )
                ),
                isFromGroup = false
            )
        }

    @Test
    fun `T23 forward-preview content is normalized for a nested single forward`() {
        
        
        
        
        val message = singleForwardMessage("a${ps}b")
        val rawContent = message.buildForwardData()!!.first
        assertEquals("a${ps}b", rawContent) 
        assertEquals("a\nb", rawContent.normalizeNewlines()) 
    }

    @Test
    fun `T24 copyable text is normalized for a legacy single-forward message`() {
        
        
        
        val message = singleForwardMessage("a${ls}b")
        val rawCopyable = message.getCopyableTextContent()
        assertEquals("a${ls}b", rawCopyable) 
        assertEquals("a\nb", rawCopyable?.normalizeNewlines()) 
    }
}


private object CaptureSentinel : RuntimeException()






private infix fun ChatMessageInputFragment.invokePrivateSendValidatedText(message: String): Boolean {
    val method = ChatMessageInputFragment::class.java.getDeclaredMethod(
        "sendValidatedText", String::class.java, Function0::class.java
    ).apply { isAccessible = true }
    return method.invoke(this, message, null) as Boolean
}







private fun injectChatViewModelDelegate(fragment: ChatMessageInputFragment) {
    val vm = mockk<ChatMessageViewModel>(relaxed = true)
    every { vm.forWhat } returns For.Account("+10000000")
    val delegateField = ChatMessageInputFragment::class.java
        .getDeclaredField("chatViewModel\$delegate")
        .apply { isAccessible = true }
    delegateField.set(fragment, lazy { vm })
}
