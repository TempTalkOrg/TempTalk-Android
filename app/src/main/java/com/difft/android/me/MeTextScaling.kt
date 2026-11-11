package com.difft.android.me

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.difft.android.databinding.MeFragmentBinding






internal object MeTextScaling {

    fun apply(binding: MeFragmentBinding, isLarger: Boolean) {
        textViewsUnder(binding.scrollView2).forEach { it.textSize = if (isLarger) 24f else 16f }
        binding.tvProfile.textSize = if (isLarger) 21f else 14f
    }

    private fun textViewsUnder(root: View): List<TextView> {
        val found = mutableListOf<TextView>()
        val stack = mutableListOf(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.size - 1)
            if (current is TextView) {
                found.add(current)
            } else if (current is ViewGroup) {
                for (i in current.childCount - 1 downTo 0) stack.add(current.getChildAt(i))
            }
        }
        return found
    }
}
