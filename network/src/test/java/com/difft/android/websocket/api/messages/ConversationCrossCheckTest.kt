package com.difft.android.websocket.api.messages

import difft.android.messageserialization.For
import org.junit.Assert.assertEquals
import org.junit.Test











class ConversationCrossCheckTest {

    private val g = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" 
    private val h = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" 
    private val a = "+15551234"                        
    private val b = "+15559999"                        

    
    @Test
    fun `CC1 group matches group processes`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Group(g), For.Group(g), isSyncOrSelf = false)
        )
    }

    
    @Test
    fun `CC2 group mismatches group rejects`() {
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(For.Group(g), For.Group(h), isSyncOrSelf = false)
        )
    }

    
    @Test
    fun `CC3 account matches account processes`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Account(a), For.Account(a), isSyncOrSelf = false)
        )
    }

    
    @Test
    fun `CC4 account mismatches account rejects`() {
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(For.Account(a), For.Account(b), isSyncOrSelf = false)
        )
    }

    
    @Test
    fun `CC5 content group vs envelope account rejects`() {
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(For.Group(g), For.Account(a), isSyncOrSelf = false)
        )
    }

    
    @Test
    fun `CC6 content account vs envelope group rejects`() {
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(For.Account(a), For.Group(g), isSyncOrSelf = false)
        )
    }

    
    @Test
    fun `CC7 absent envelope group processes`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Group(g), null, isSyncOrSelf = false)
        )
    }

    
    @Test
    fun `CC8 absent envelope account processes`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Account(a), null, isSyncOrSelf = false)
        )
    }

    
    @Test
    fun `CC9 sync or self wins over mismatch`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Account(a), For.Account(b), isSyncOrSelf = true)
        )
    }

    
    @Test
    fun `CC10 sync or self with absent processes`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Group(g), null, isSyncOrSelf = true)
        )
    }

    
    
    
    @Test
    fun `CC11 same id different shape rejects`() {
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(For.Group("x"), For.Account("x"), isSyncOrSelf = false)
        )
    }
}
