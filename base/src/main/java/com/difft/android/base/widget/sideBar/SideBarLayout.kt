package com.difft.android.base.widget.sideBar


internal sealed interface SideBarSlot {
    
    data class Letter(val index: Int) : SideBarSlot

    
    data class Dot(val fromIndex: Int, val toIndex: Int) : SideBarSlot
}


internal object SideBarLayout {
    
    const val MIN_SLOTS = 3

    





    fun slots(letterCount: Int, available: Float, minItemHeight: Float): List<SideBarSlot> {
        if (letterCount <= 0 || available <= 0f || minItemHeight <= 0f) return emptyList()
        val fit = (available / minItemHeight).toInt()
        if (letterCount <= fit) return List(letterCount) { SideBarSlot.Letter(it) }
        if (fit < MIN_SLOTS) return emptyList()
        
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
