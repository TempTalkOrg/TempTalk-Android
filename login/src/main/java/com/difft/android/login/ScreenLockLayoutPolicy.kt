package com.difft.android.login

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.PatternLockView
import com.difft.android.base.widget.PatternScreenSpacing
import com.difft.android.login.databinding.ActivityScreenLockBinding














class ScreenLockLayoutPolicy(private val binding: ActivityScreenLockBinding) {

    private val logoLp = binding.ivLogo.layoutParams as ConstraintLayout.LayoutParams
    private val titleLp = binding.tvTitle.layoutParams as ConstraintLayout.LayoutParams
    private val patternLp = binding.patternLockView.layoutParams as ConstraintLayout.LayoutParams
    private val passcodeLogoTopMargin = logoLp.topMargin
    private val passcodeTitleGoneTopMargin = titleLp.goneTopMargin

    fun attach() {
        
        
        
        
        binding.root.viewTreeObserver.addOnPreDrawListener { !evaluate() }
    }

    
    private fun evaluate(): Boolean {
        val root = binding.root
        if (root.width == 0 || root.height == 0) return false
        if (binding.patternLockView.visibility != View.VISIBLE) {
            return applyMargins(passcodeLogoTopMargin, passcodeTitleGoneTopMargin, patternLp.topMargin)
        }
        val density = root.resources.displayMetrics.density
        
        val widthBudget = root.width - root.paddingLeft - root.paddingRight -
            patternLp.marginStart - patternLp.marginEnd
        val optionsLp = binding.llOptions.layoutParams as ConstraintLayout.LayoutParams
        val logoBlock = PatternScreenSpacing.dp(LOGO_BLOCK_DP, density)
        val spacing = PatternScreenSpacing.compute(
            density = density,
            available = root.height - root.paddingTop - root.paddingBottom,
            header = binding.tvTitle.height,
            footer = binding.llOptions.height + optionsLp.bottomMargin,
            grid = minOf(widthBudget, PatternScreenSpacing.dp(PatternLockView.MAX_SIZE_DP, density)),
            logoBlock = logoBlock,
        )

        val logoGone = binding.ivLogo.visibility == View.GONE
        val visibilityChanged = spacing.showLogo == logoGone
        if (visibilityChanged) {
            L.i { "[ScreenLock] logo hidden=${!spacing.showLogo} root=${root.width}x${root.height}" }
            binding.ivLogo.visibility = if (spacing.showLogo) View.VISIBLE else View.GONE
        }
        
        val logoTopMargin = spacing.top + logoBlock - logoLp.height - titleLp.topMargin
        
        val backHeight = if (binding.ibBack.visibility == View.VISIBLE) binding.ibBack.height else 0
        
        
        val tipsLp = binding.tvErrorTips.layoutParams as ConstraintLayout.LayoutParams
        val patternTop = maxOf(spacing.headerToGrid, tipsLp.topMargin + binding.tvErrorTips.height)
        val marginsChanged = applyMargins(logoTopMargin, maxOf(spacing.top, backHeight), patternTop)
        return visibilityChanged || marginsChanged
    }

    private fun applyMargins(logoTop: Int, titleGoneTop: Int, patternTop: Int): Boolean {
        if (logoLp.topMargin == logoTop && titleLp.goneTopMargin == titleGoneTop && patternLp.topMargin == patternTop) {
            return false
        }
        logoLp.topMargin = logoTop
        titleLp.goneTopMargin = titleGoneTop
        patternLp.topMargin = patternTop
        
        
        binding.ivLogo.layoutParams = logoLp
        binding.tvTitle.layoutParams = titleLp
        binding.patternLockView.layoutParams = patternLp
        return true
    }

    private companion object {
        
        const val LOGO_BLOCK_DP = 160
    }
}
