package com.difft.android.login

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.PatternLockView
import com.difft.android.base.widget.PatternScreenSpacing
import com.difft.android.login.databinding.ActivityScreenLockBinding

/**
 * Vertical layout of the unlock screen in pattern mode ([PatternScreenSpacing]): centers the
 * header, grid and options and hides the logo on short windows (squat foldables, small phones,
 * multi-window) so the grid keeps its full size.
 *
 * Evaluated only while the pattern is shown. The root is edge-to-edge and padded for the system
 * bars only, so its height does not change with the keyboard. Passcode mode keeps the layout's own
 * top-anchored margins so its field and button stay above the keyboard; the logo visibility
 * carries over unchanged, and passcode-only users never lose the logo.
 *
 * Every input (root size, title and options heights, the grid's full size) is independent of
 * the margins it sets, so the re-layout after a change evaluates to the same values.
 */
class ScreenLockLayoutPolicy(private val binding: ActivityScreenLockBinding) {

    private val logoLp = binding.ivLogo.layoutParams as ConstraintLayout.LayoutParams
    private val titleLp = binding.tvTitle.layoutParams as ConstraintLayout.LayoutParams
    private val patternLp = binding.patternLockView.layoutParams as ConstraintLayout.LayoutParams
    private val passcodeLogoTopMargin = logoLp.topMargin
    private val passcodeTitleGoneTopMargin = titleLp.goneTopMargin

    fun attach() {
        // Decide before draw, outside the layout pass. Toggling a child's visibility from a
        // layout callback during the first layout leaves ConstraintLayout's solver un-dirtied:
        // the logo goes GONE but the title stays laid out against it. Skipping the frame after
        // a change re-runs measure/layout cleanly.
        binding.root.viewTreeObserver.addOnPreDrawListener { !evaluate() }
    }

    /** Returns true when the layout changed and the pending frame must be re-laid out. */
    private fun evaluate(): Boolean {
        val root = binding.root
        if (root.width == 0 || root.height == 0) return false
        if (binding.patternLockView.visibility != View.VISIBLE) {
            return applyMargins(passcodeLogoTopMargin, passcodeTitleGoneTopMargin, patternLp.topMargin)
        }
        val density = root.resources.displayMetrics.density
        // Root carries the system-bar insets as padding; measure between the padded edges.
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
        // The logo image has no padding: place it in the block the design reserves for it.
        val logoTopMargin = spacing.top + logoBlock - logoLp.height - titleLp.topMargin
        // Without the logo, keep the title below the back button shown in verification mode.
        val backHeight = if (binding.ibBack.visibility == View.VISIBLE) binding.ibBack.height else 0
        // The error tips sit in the title-to-grid gap; a long warning in a narrow window can need
        // more than the design's minimum gap.
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
        // Re-set the params: ConstraintLayout re-reads a child's params only when that child
        // requests layout, so requestLayout() on the root alone keeps the old margins.
        binding.ivLogo.layoutParams = logoLp
        binding.tvTitle.layoutParams = titleLp
        binding.patternLockView.layoutParams = patternLp
        return true
    }

    private companion object {
        // The design's logo box; the logo drawable itself is 94dp tall with no padding.
        const val LOGO_BLOCK_DP = 160
    }
}
