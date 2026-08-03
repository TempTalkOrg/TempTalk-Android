package com.difft.android.base.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T1-T12 — pure-function tests for [String.normalizeNewlines] (send-side canonical).
 * D1-D5  — pure-function tests for [String.normalizeNewlinesForDisplay] (display-side,
 *          strictly length-preserving so it never shifts the sender's mention offsets).
 *
 * Plain JUnit (mirrors [FlowExtensionsTest] — no @RunWith / Robolectric); both
 * subjects are pure `String -> String` transforms that touch no Android API.
 *
 * Every test invokes the REAL production extension (never a re-implementation).
 * T1-T12 cover each of the 7 family members individually (LS/PS/CRLF/CR/VT/FF/NEL),
 * PS-not-doubled, CRLF 2->1 length shrink, idempotency, mixed sequence, empty,
 * ASCII+`\n` unchanged, and the 1->1 vs 2->1 length-invariant distinction.
 * D1-D5 cover the display variant: soft separators + lone CR -> `\n`, CRLF left
 * intact (not collapsed, not doubled), and the offset-preservation length guarantee.
 *
 * Family members are written via `Char(codepoint)` so this source file
 * contains no raw control characters.
 */
class NewlineNormalizerTest {

    private val ls = Char(0x2028).toString()  // LINE SEPARATOR
    private val ps = Char(0x2029).toString()  // PARAGRAPH SEPARATOR
    private val cr = Char(0x000D).toString()  // CARRIAGE RETURN (bare)
    private val vt = Char(0x000B).toString()  // VERTICAL TAB
    private val ff = Char(0x000C).toString()  // FORM FEED
    private val nel = Char(0x0085).toString() // NEXT LINE

    // ---- T1: LS member (1->1) ----
    @Test
    fun `T1 LS becomes single newline length preserved`() {
        val result = "a${ls}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(3, result.length) // 1->1, length preserved
    }

    // ---- T2: PS member, NOT doubled (core spec guard) ----
    @Test
    fun `T2 PS becomes single newline never doubled`() {
        val result = "a${ps}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertNotEquals("a\n\nb", result) // explicit: PS is one break, not two
        assertEquals(3, result.length)
    }

    // ---- T3: CRLF (2->1) with length shrink ----
    @Test
    fun `T3 CRLF becomes single newline length shrinks by one`() {
        val input = "a\r\nb"
        val result = input.normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(4, input.length)  // input is 4 chars
        assertEquals(3, result.length) // output is 3 chars (shrinks by 1)
    }

    // ---- T4: lone CR member (1->1) ----
    @Test
    fun `T4 lone CR becomes single newline length preserved`() {
        val result = "a${cr}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(3, result.length)
    }

    // ---- T5: VT member (1->1) ----
    @Test
    fun `T5 VT becomes single newline`() {
        val result = "a${vt}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(3, result.length)
    }

    // ---- T6: FF member (1->1) ----
    @Test
    fun `T6 FF becomes single newline`() {
        val result = "a${ff}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(3, result.length)
    }

    // ---- T7: NEL member (1->1) ----
    @Test
    fun `T7 NEL becomes single newline`() {
        val result = "a${nel}b".normalizeNewlines()
        assertEquals("a\nb", result)
        assertEquals(3, result.length)
    }

    // ---- T8: mixed sequence, CRLF matched as one unit first ----
    @Test
    fun `T8 mixed sequence maps each member to one newline CRLF as unit`() {
        // 7 separators: CRLF(->1) + VT + FF + NEL + LS + PS + lone CR = 7 resulting `\n`.
        // (The design §9.2a note's "8" is an arithmetic slip: it lists 7 members but
        // sums to 8; the correct one-for-one result for these 7 separators is 7 `\n`.)
        val input = "a\r\n$vt$ff$nel$ls$ps${cr}b"
        val result = input.normalizeNewlines()
        assertEquals("a\n\n\n\n\n\n\nb", result)   // 7 `\n` between a and b
        assertEquals(7, result.count { it == '\n' }) // CRLF as ONE unit, never `\n\n`
    }

    // ---- T9: idempotency ----
    @Test
    fun `T9 second pass is identity`() {
        val s = "a\r\n${vt}b${ff}c${nel}d${ls}e${ps}f${cr}g" // contains every member
        val once = s.normalizeNewlines()
        val twice = s.normalizeNewlines().normalizeNewlines()
        assertEquals(once, twice)
    }

    // ---- T10: empty string ----
    @Test
    fun `T10 empty string returns empty`() {
        assertEquals("", "".normalizeNewlines())
    }

    // ---- T11: pure ASCII + real `\n` unchanged ----
    @Test
    fun `T11 ascii and real newline unchanged`() {
        val input = "plain ascii text 123 with\nreal newline"
        assertEquals(input, input.normalizeNewlines()) // `\n` is not a family member
    }

    // ---- T12: length-invariant pair distinguishing 1->1 from 2->1 ----
    @Test
    fun `T12 length invariants distinguish one-to-one from two-to-one`() {
        val oneToOne = "a${ls}b"   // input length 3
        val twoToOne = "a\r\nb"     // input length 4
        // 1->1 member: output length == INPUT length (preserved).
        assertEquals(oneToOne.length, oneToOne.normalizeNewlines().length)
        assertEquals(3, oneToOne.normalizeNewlines().length)
        // 2->1 member: output length == INPUT length - 1 (shrinks by exactly the CRLF collapse).
        assertEquals(twoToOne.length - 1, twoToOne.normalizeNewlines().length)
        assertEquals(3, twoToOne.normalizeNewlines().length)
        // The two produce the same 3-char output "a\nb", but from different-length
        // inputs — that IS the 1->1 vs 2->1 distinction (preserved vs shrunk).
        assertTrue(twoToOne.length > oneToOne.length)
    }

    // ---- Display variant: normalizeNewlinesForDisplay (strictly length-preserving) ----
    // Maps lone CR + soft separators (VT/FF/NEL/LS/PS) to `\n` but leaves CRLF intact,
    // so mention offsets that index the sender's body are never shifted.

    // ---- D1: each soft separator (VT/FF/NEL/LS/PS) -> `\n`, length unchanged ----
    @Test
    fun `D1 soft separators map to newline with length preserved`() {
        val members = mapOf("VT" to vt, "FF" to ff, "NEL" to nel, "LS" to ls, "PS" to ps)
        members.forEach { (name, ch) ->
            val result = "a${ch}b".normalizeNewlinesForDisplay()
            assertEquals("a\nb", result, "$name should map to a single \\n")
            assertEquals(3, result.length, "$name is 1->1, length preserved")
        }
    }

    // ---- D2: lone CR -> `\n` (1->1) ----
    @Test
    fun `D2 lone CR becomes newline length preserved`() {
        val result = "a\rb".normalizeNewlinesForDisplay()
        assertEquals("a\nb", result)
        assertEquals(3, result.length)
    }

    // ---- D3: CRLF preserved (NOT collapsed, NOT doubled) — the offset-safety guard ----
    @Test
    fun `D3 CRLF is left intact never collapsed nor doubled`() {
        val result = "a\r\nb".normalizeNewlinesForDisplay()
        assertEquals("a\r\nb", result)      // untouched: `\r\n` already renders as a break
        assertNotEquals("a\n\nb", result)   // explicit: not doubled
        assertNotEquals("a\nb", result)     // explicit: not collapsed
        assertEquals(4, result.length)      // strictly length-preserving
    }

    // ---- D4: a CRLF-before-text body keeps its length (offset-preservation guarantee) ----
    @Test
    fun `D4 body with CRLF before text stays the same length`() {
        val body = "\r\n@Alice hi" // CRLF then a mention; sender offsets index this body
        val result = body.normalizeNewlinesForDisplay()
        assertEquals(body.length, result.length) // length unchanged -> offsets stay valid
        assertEquals(body, result)                // CRLF-before-text is fully preserved
    }

    // ---- D5: real `\n` and plain ASCII unchanged ----
    @Test
    fun `D5 real newline and ascii unchanged`() {
        val input = "plain ascii 123 with\nreal newline"
        assertEquals(input, input.normalizeNewlinesForDisplay()) // `\n` is not a member
    }
}
