package com.difft.android.setting

import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.widget.PatternLockView
import com.difft.android.base.widget.PatternScreenSpacing
import com.difft.android.databinding.ActivitySetPatternBinding

/**
 * Vertical layout of the set-pattern screen: the header stays at the top and the grid is centered
 * between the header and the tips, at least [PatternScreenSpacing.MIN_HEADER_TO_GRID_DP] from
 * each (the grid's bottom margin in the layout); a short window shrinks the grid.
 *
 * Computed once per window geometry (size, system-bar padding, title bar and tips heights): the
 * header grows when an error replaces the subtitle, and the grid must not move under the finger
 * while a pattern is being drawn, so the grid is anchored to the title bar and the header
 * measured at the first layout keeps defining its position.
 */
class SetPatternLayoutPolicy(private val binding: ActivitySetPatternBinding) {

    private var laidOutGeometry: List<Int>? = null

    fun attach() {
        // Decide before draw and skip the frame after a change so it is re-laid out first.
        binding.root.viewTreeObserver.addOnPreDrawListener { !evaluate() }
    }

    /** Returns true when the layout changed and the pending frame must be re-laid out. */
    private fun evaluate(): Boolean {
        val root = binding.root
        if (root.width == 0 || root.height == 0) return false
        val geometry = listOf(
            root.width, root.height, root.paddingTop, root.paddingBottom,
            binding.clTitle.height, binding.clTips.height,
        )
        if (geometry == laidOutGeometry) return false
        laidOutGeometry = geometry

        val density = root.resources.displayMetrics.density
        val headerLp = binding.llHeader.layoutParams as ConstraintLayout.LayoutParams
        val patternLp = binding.patternLockView.layoutParams as ConstraintLayout.LayoutParams
        // Root carries the system-bar insets as padding; measure between the padded edges.
        val widthBudget = root.width - root.paddingLeft - root.paddingRight -
            patternLp.marginStart - patternLp.marginEnd
        val grid = minOf(widthBudget, PatternScreenSpacing.dp(PatternLockView.MAX_SIZE_DP, density))
        val headerBottom = headerLp.topMargin + binding.llHeader.height
        val space = root.height - root.paddingTop - root.paddingBottom - binding.clTitle.height -
            headerBottom - binding.clTips.height
        val minGap = PatternScreenSpacing.dp(PatternScreenSpacing.MIN_HEADER_TO_GRID_DP, density)
        val patternTop = headerBottom + maxOf(minGap, (space - grid) / 2)

        if (patternLp.topMargin == patternTop) return false
        patternLp.topMargin = patternTop
        // Re-set the params: ConstraintLayout re-reads a child's params only when that child
        // requests layout, so requestLayout() on the root alone keeps the old margins.
        binding.patternLockView.layoutParams = patternLp
        return true
    }
}
