package com.difft.android.base.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue






class NewlineNormalizerTest {

    private val ls = Char(0x2028).toString()  
    private val ps = Char(0x2029).toString()  
    private val cr = Char(0x000D).toString()  
    private val vt = Char(0x000B).toString()  
    private val ff = Char(0x000C).toString()  
    private val nel = Char(0x0085).toString() 

    
    @Test
    fun `T1 LS becomes single newline length preserved`() {
        val result = "a${ls}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(3, result.length) 
    }

    
    @Test
    fun `T2 PS becomes single newline never doubled`() {
        val result = "a${ps}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertNotEquals("a\n\nb", result) 
        assertEquals(3, result.length)
    }

    
    @Test
    fun `T3 CRLF becomes single newline length shrinks by one`() {
        val input = "a\r\nb"
        val result = input.normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(4, input.length)  
        assertEquals(3, result.length) 
    }

    
    @Test
    fun `T4 lone CR becomes single newline length preserved`() {
        val result = "a${cr}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(3, result.length)
    }

    
    @Test
    fun `T5 VT becomes single newline`() {
        val result = "a${vt}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(3, result.length)
    }

    
    @Test
    fun `T6 FF becomes single newline`() {
        val result = "a${ff}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(3, result.length)
    }

    
    @Test
    fun `T7 NEL becomes single newline`() {
        val result = "a${nel}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(3, result.length)
    }

    
    @Test
    fun `T8 mixed sequence maps each member to one newline CRLF as unit`() {
        
        val input = "a\r\n$vt$ff$nel$ls$ps${cr}b"
        val result = input.normalizeNewlines()
        assertEquals("a\n\n\n\n\n\n\nb", result)   
        assertEquals(7, result.count { it == '\n' }) 
    }

    
    @Test
    fun `T9 second pass is identity`() {
        val s = "a\r\n${vt}b${ff}c${nel}d${ls}e${ps}f${cr}g" 
        val once = s.normalizeNewlines()
        val twice = s.normalizeNewlines().normalizeNewlines()
        assertEquals(once, twice)
    }

    
    @Test
    fun `T10 empty string returns empty`() {
        assertEquals("", "".normalizeNewlines())
    }

    
    @Test
    fun `T11 ascii and real newline unchanged`() {
        val input = "plain ascii text 123 with\nreal newline"
        assertEquals(input, input.normalizeNewlines()) 
    }

    
    @Test
    fun `T12 length invariants distinguish one-to-one from two-to-one`() {
        val oneToOne = "a${ls}b"   
        val twoToOne = "a\r\nb"     
        
        assertEquals(oneToOne.length, oneToOne.normalizeNewlines().length)
        assertEquals(3, oneToOne.normalizeNewlines().length)
        
        assertEquals(twoToOne.length - 1, twoToOne.normalizeNewlines().length)
        assertEquals(3, twoToOne.normalizeNewlines().length)
        
        
        assertTrue(twoToOne.length > oneToOne.length)
    }

    
    
    

    
    @Test
    fun `D1 soft separators map to newline with length preserved`() {
        val members = mapOf("VT" to vt, "FF" to ff, "NEL" to nel, "LS" to ls, "PS" to ps)
        members.forEach { (name, ch) ->
            val result = "a${ch}b".normalizeNewlinesForDisplay()
            assertEquals("a\nb", result, "$name should map to a single \\n")
            assertEquals(3, result.length, "$name is 1->1, length preserved")
        }
    }

    
    @Test
    fun `D2 lone CR becomes newline length preserved`() {
        val result = "a\rb".normalizeNewlinesForDisplay()
        assertEquals("a\nb", result)
        assertEquals(3, result.length)
    }

    
    @Test
    fun `D3 CRLF is left intact never collapsed nor doubled`() {
        val result = "a\r\nb".normalizeNewlinesForDisplay()
        assertEquals("a\r\nb", result)      
        assertNotEquals("a\n\nb", result)   
        assertNotEquals("a\nb", result)     
        assertEquals(4, result.length)      
    }

    
    @Test
    fun `D4 body with CRLF before text stays the same length`() {
        val body = "\r\n@Alice hi" 
        val result = body.normalizeNewlinesForDisplay()
        assertEquals(body.length, result.length) 
        assertEquals(body, result)                
    }

    
    @Test
    fun `D5 real newline and ascii unchanged`() {
        val input = "plain ascii 123 with\nreal newline"
        assertEquals(input, input.normalizeNewlinesForDisplay()) 
    }
}
