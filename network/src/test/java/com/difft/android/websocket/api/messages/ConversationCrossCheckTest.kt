package com.difft.android.websocket.api.messages

import difft.android.messageserialization.For
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [crossCheckConversation] — the pure decision core.
 *
 * Zero framework setup: the function depends only on [For] (pure Kotlin) and has
 * no proto / lazy / Context / globalServices deps, so every branch is covered on
 * the host JVM. Covers CC1-11 from design-report §7.1: present-match / mismatch
 * (Group & Account), cross-shape mismatch (both directions), absent fail-open,
 * sync/self exemption winning over mismatch, and same-id-different-shape (proves
 * For.equals uses typeValue, not id alone).
 */
class ConversationCrossCheckTest {

    private val g = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // group id G
    private val h = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" // group id H
    private val a = "+15551234"                        // account A
    private val b = "+15559999"                        // account B

    // CC1 — present & match, Group=Group -> PROCESS
    @Test
    fun `CC1 group matches group processes`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Group(g), For.Group(g), isSyncOrSelf = false)
        )
    }

    // CC2 — present & mismatch, gid differs -> REJECT
    @Test
    fun `CC2 group mismatches group rejects`() {
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(For.Group(g), For.Group(h), isSyncOrSelf = false)
        )
    }

    // CC3 — present & match, Account=Account -> PROCESS
    @Test
    fun `CC3 account matches account processes`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Account(a), For.Account(a), isSyncOrSelf = false)
        )
    }

    // CC4 — present & mismatch, 1v1 differs -> REJECT
    @Test
    fun `CC4 account mismatches account rejects`() {
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(For.Account(a), For.Account(b), isSyncOrSelf = false)
        )
    }

    // CC5 — cross-shape: content Group vs envelope Account -> REJECT
    @Test
    fun `CC5 content group vs envelope account rejects`() {
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(For.Group(g), For.Account(a), isSyncOrSelf = false)
        )
    }

    // CC6 — cross-shape reverse: content Account vs envelope Group -> REJECT (symmetry)
    @Test
    fun `CC6 content account vs envelope group rejects`() {
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(For.Account(a), For.Group(g), isSyncOrSelf = false)
        )
    }

    // CC7 — absent envelope, group content -> fail-open PROCESS
    @Test
    fun `CC7 absent envelope group processes`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Group(g), null, isSyncOrSelf = false)
        )
    }

    // CC8 — absent envelope, 1v1 content -> fail-open PROCESS
    @Test
    fun `CC8 absent envelope account processes`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Account(a), null, isSyncOrSelf = false)
        )
    }

    // CC9 — sync/self exemption WINS over a present mismatch -> PROCESS
    @Test
    fun `CC9 sync or self wins over mismatch`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Account(a), For.Account(b), isSyncOrSelf = true)
        )
    }

    // CC10 — exemption + absent -> PROCESS
    @Test
    fun `CC10 sync or self with absent processes`() {
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(For.Group(g), null, isSyncOrSelf = true)
        )
    }

    // CC11 — same id string, different shape -> REJECT
    // Proves For.equals compares typeValue, not id alone; if it ever regresses to
    // id-only this test flips and flags a silent cross-shape injection hole.
    @Test
    fun `CC11 same id different shape rejects`() {
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(For.Group("x"), For.Account("x"), isSyncOrSelf = false)
        )
    }
}
