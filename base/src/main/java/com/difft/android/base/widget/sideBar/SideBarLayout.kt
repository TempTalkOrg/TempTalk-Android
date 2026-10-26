package com.difft.android.base.widget.sideBar

/** One drawn position on the [SideBar] rail. */
internal sealed interface SideBarSlot {
    /** Draws the letter at [index] of the full letter list. */
    data class Letter(val index: Int) : SideBarSlot

    /** Draws a dot standing for the hidden letters [fromIndex]..[toIndex] (inclusive). */
    data class Dot(val fromIndex: Int, val toIndex: Int) : SideBarSlot
}

/** Pure fit and hit-test logic for [SideBar], kept separate so it can be unit tested. */
internal object SideBarLayout {
    /** When condensing is needed but fewer slots than this fit, nothing is drawn. */
    const val MIN_SLOTS = 3

    /**
     * Returns the slots to draw for [letterCount] letters in [available] px, each slot at least
     * [minItemHeight] px. When every letter fits, each gets its own slot; otherwise letters and
     * dots alternate (UIKit-style) with the first and last letters kept, or an empty list when
     * not even [MIN_SLOTS] slots fit.
     */
    fun slots(letterCount: Int, available: Float, minItemHeight: Float): List<SideBarSlot> {
        if (letterCount <= 0 || available <= 0f || minItemHeight <= 0f) return emptyList()
        val fit = (available / minItemHeight).toInt()
        if (letterCount <= fit) return List(letterCount) { SideBarSlot.Letter(it) }
        if (fit < MIN_SLOTS) return emptyList()
        // Odd slot count so the rail starts and ends with a letter.
        val slotCount = if (fit % 2 == 0) fit - 1 else fit
        val visible = (slotCount + 1) / 2
        val picked = List(visible) { i -> (i.toLong() * (letterCount - 1) / (visible - 1)).toInt() }
        return buildList {
            picked.forEachIndexed { i, index ->
                add(SideBarSlot.Letter(index))
                val next = picked.getOrNull(i + 1) ?: return@forEachIndexed
                if (next - index > 1) add(SideBarSlot.Dot(index + 1, next - 1))
            }
        }
    }

    /**
     * Maps [y] (px from the top of the first slot) to an index of the full letter list, or -1.
     * A letter slot selects its own letter; a dot slot spreads over the letters it hides, so
     * dragging across it still visits each of them.
     */
    fun letterIndexAt(slots: List<SideBarSlot>, slotHeight: Float, y: Float): Int {
        if (slots.isEmpty() || slotHeight <= 0f) return -1
        val position = (y / slotHeight).coerceIn(0f, slots.size.toFloat())
        val slotIndex = position.toInt().coerceAtMost(slots.lastIndex)
        return when (val slot = slots[slotIndex]) {
            is SideBarSlot.Letter -> slot.index
            is SideBarSlot.Dot -> {
                val fraction = (position - slotIndex).coerceIn(0f, 1f)
                val count = slot.toIndex - slot.fromIndex + 1
                slot.fromIndex + (fraction * count).toInt().coerceAtMost(count - 1)
            }
        }
    }
}
