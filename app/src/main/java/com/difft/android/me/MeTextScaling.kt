package com.difft.android.me

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.difft.android.databinding.MeFragmentBinding

/**
 * The Me page's "Text size" setting: every text of the page content scales (24sp large / 16sp
 * default, the profile line 21/14). The page header (title, rail toggle) is not content and keeps
 * the layout's size, so the title stays aligned with the other tabs' and the detail pane's titles.
 */
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
