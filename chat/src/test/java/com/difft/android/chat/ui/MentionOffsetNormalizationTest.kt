package com.difft.android.chat.ui

import com.difft.android.base.utils.normalizeNewlines
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config










@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MentionOffsetNormalizationTest {

    
    
    private val fragment = ChatMessageInputFragment()

    private val aliceKey = "@Alice"

    
    @Test
    fun `T13 LS before mention leaves offset unchanged and start indexes at-sign`() {
        val ls = Char(0x2028).toString() 
        val rawBody = "$ls${aliceKey} hi"          
        val normalizedBody = rawBody.normalizeNewlines()
        assertEquals("\n@Alice hi", normalizedBody) 

        
        val rawOffsets = fragment.mentionOffsets(rawBody, setOf(aliceKey))
        
        val normalizedOffsets = fragment.mentionOffsets(normalizedBody, setOf(aliceKey))

        assertEquals(1, normalizedOffsets.size)
        val m = normalizedOffsets.single()
        assertEquals(1, m.start)                       
        assertEquals(aliceKey.length, m.length)        
        
        assertEquals(rawOffsets.single().start, m.start)
        assertEquals('@', normalizedBody[m.start])     
    }

    
    @Test
    fun `T14 CRLF before mention shifts start by minus one and re-indexes at-sign`() {
        val rawBody = "\r\n${aliceKey} hi"        
        
        val rawOffsets = fragment.mentionOffsets(rawBody, setOf(aliceKey))
        assertEquals(2, rawOffsets.single().start)

        val normalizedBody = rawBody.normalizeNewlines()
        assertEquals("\n@Alice hi", normalizedBody)   

        val normalizedOffsets = fragment.mentionOffsets(normalizedBody, setOf(aliceKey))
        val m = normalizedOffsets.single()
        assertEquals(1, m.start)                       
        assertEquals(aliceKey.length, m.length)
        assertEquals('@', normalizedBody[m.start])     
    }

    
    @Test
    fun `T14b offsets against truncated body never exceed its length and drop tail mentions`() {
        val bobKey = "@Bob"
        
        val fullBody = "$aliceKey said hi to $bobKey over there "
        
        val cut = fullBody.indexOf(bobKey)
        val truncatedBody = fullBody.substring(0, cut).normalizeNewlines()
        assertTrue("truncated prefix must still contain @Alice", truncatedBody.contains(aliceKey))
        assertTrue("truncated prefix must NOT contain @Bob", !truncatedBody.contains(bobKey))

        val offsets = fragment.mentionOffsets(truncatedBody, setOf(aliceKey, bobKey))

        
        offsets.forEach { m ->
            assertTrue("start >= 0", m.start >= 0)
            assertTrue(
                "start + length (${m.start + m.length}) <= truncatedBody.length (${truncatedBody.length})",
                m.start + m.length <= truncatedBody.length
            )
        }
        
        assertNull("tail mention @Bob must be dropped", offsets.find { it.length == bobKey.length })
        val alice = offsets.single { it.length == aliceKey.length }
        assertEquals('@', truncatedBody[alice.start])
    }

    
    @Test
    fun `T19 send-side rebuilt offset indexes at-sign independent of stale draft offset`() {
        
        
        
        val draftRawBody = "\r\n${aliceKey} draft text"
        val staleDraftOffset = 2 

        
        val normalizedAtSend = draftRawBody.normalizeNewlines()
        val rebuilt = fragment.mentionOffsets(normalizedAtSend, setOf(aliceKey))

        val m = rebuilt.single()
        
        assertEquals(1, m.start)
        assertTrue("rebuilt offset must differ from the stale draft offset", m.start != staleDraftOffset)
        assertEquals('@', normalizedAtSend[m.start]) 
    }
}
