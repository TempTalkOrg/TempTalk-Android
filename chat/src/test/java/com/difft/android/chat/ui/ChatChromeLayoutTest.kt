package com.difft.android.chat.ui

import com.difft.android.chat.ui.messageaction.ChatChromeInsets
import org.junit.Assert.assertEquals
import org.junit.Test








class ChatChromeLayoutTest {

    private var clock = 0L
    private val jump = JumpTargetAnchor(now = { clock }, holdMs = 1_000)

    @Test
    fun `a shrinking bottom scrolls by the same amount`() {
        assertEquals(ListReanchor.ScrollBy(120), jump.reanchorForBottomChange(120))
    }

    @Test
    fun `a growing bottom leaves the list alone`() {
        assertEquals(ListReanchor.None, jump.reanchorForBottomChange(-120))
    }

    @Test
    fun `a taller input or a quote bar scrolls the list by the inset change`() {
        val before = ChatChromeInsets(top = 100, bottom = 60)

        assertEquals(ListReanchor.ScrollBy(56), jump.reanchorForChrome(before, before.copy(bottom = 116)))
        assertEquals(ListReanchor.None, jump.reanchorForChrome(before.copy(bottom = 116), before))
    }

    @Test
    fun `the first insets after layout keep the bottom in view`() {
        assertEquals(ListReanchor.ScrollBy(60), jump.reanchorForChrome(ChatChromeInsets.NONE, ChatChromeInsets(top = 100, bottom = 60)))
    }

    @Test
    fun `a pending jump keeps the bottom on a bottom loss and is applied again otherwise`() {
        jump.set(42)
        clock = 300
        val before = ChatChromeInsets(top = 100, bottom = 60)

        assertEquals(ListReanchor.ScrollByKeeping(56, 42), jump.reanchorForChrome(before, before.copy(bottom = 116)))
        assertEquals(ListReanchor.ToPosition(42), jump.reanchorForChrome(before.copy(bottom = 116), before))
        assertEquals(ListReanchor.ToPosition(42), jump.reanchorForChrome(before, before.copy(top = 144)))
    }

    @Test
    fun `the first insets after an open on the first unread keep the bottom in view`() {
        jump.set(16)
        clock = 50

        assertEquals(ListReanchor.ScrollByKeeping(60, 16), jump.reanchorForChrome(ChatChromeInsets.NONE, ChatChromeInsets(top = 100, bottom = 60)))
    }

    @Test
    fun `a top-only change without a pending jump keeps the content in place`() {
        val before = ChatChromeInsets(top = 100, bottom = 60)

        assertEquals(ListReanchor.None, jump.reanchorForChrome(before, before.copy(top = 144)))
        assertEquals(ListReanchor.None, jump.reanchorForChrome(before, before))
    }
}
