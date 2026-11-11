package com.difft.android.setting

import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.widget.PatternLockView
import com.difft.android.base.widget.PatternScreenSpacing
import com.difft.android.databinding.ActivitySetPatternBinding











class SetPatternLayoutPolicy(private val binding: ActivitySetPatternBinding) {

    private var laidOutGeometry: List<Int>? = null

    fun attach() {
        
        binding.root.viewTreeObserver.addOnPreDrawListener { !evaluate() }
    }

    
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
        
        
        binding.patternLockView.layoutParams = patternLp
        return true
    }
}
