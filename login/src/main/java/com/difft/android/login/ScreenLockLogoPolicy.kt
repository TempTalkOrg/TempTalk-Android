package com.difft.android.login

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.login.databinding.ActivityScreenLockBinding

/**
 * Hides the logo on short windows (squat foldables, small phones, multi-window) so the
 * pattern grid keeps a usable size instead of being squeezed by the fixed header.
 *
 * Evaluated only while the pattern is shown: that is the mode whose geometry matters and
 * the keyboard is always dismissed there, so the root height is not shrunk by adjustResize.
 * The decision carries over to passcode mode unchanged, so switching modes never moves the
 * header, and passcode-only users never lose the logo.
 *
 * The height budget is normalised to "logo shown" before comparing (the freed header block
 * is added back when the logo is already GONE), so the re-layout caused by the decision
 * re-evaluates to the same answer and the logo does not oscillate.
 */
class ScreenLockLogoPolicy(private val binding: ActivityScreenLockBinding) {

    private val minPatternSize =
        binding.root.resources.getDimensionPixelSize(R.dimen.screen_lock_pattern_min_size)

    fun attach() {
        // Decide before draw, outside the layout pass. Toggling a child's visibility from a
        // layout callback during the first layout leaves ConstraintLayout's solver un-dirtied:
        // the logo goes GONE but the title stays laid out against it. Skipping the frame after
        // a change re-runs measure/layout cleanly.
        binding.root.viewTreeObserver.addOnPreDrawListener { !evaluate() }
    }

    /** Returns true when the logo visibility changed and the pending frame must be re-laid out. */
    private fun evaluate(): Boolean {
        val root = binding.root
        if (root.width == 0 || root.height == 0) return false
        if (binding.patternLockView.visibility != View.VISIBLE) return false
        // The keyboard may still be closing right after a passcode -> pattern switch.
        val imeVisible = ViewCompat.getRootWindowInsets(root)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) return false

        val patternLp = binding.patternLockView.layoutParams as ConstraintLayout.LayoutParams
        // Root carries the system-bar insets as padding; measure between the padded edges.
        val widthBudget = root.width - root.paddingLeft - root.paddingRight -
            patternLp.marginStart - patternLp.marginEnd
        // On narrow screens the width already caps the grid; do not drop the logo for space
        // the pattern could never use.
        val requiredSize = minOf(minPatternSize, widthBudget)

        // In pattern mode the options row is pinned above the bottom padding, so its top is
        // the pattern's real lower bound (nav-bar inset included).
        val heightBudget = binding.llOptions.top -
            binding.tvErrorTips.bottom -
            patternLp.topMargin -
            patternLp.bottomMargin
        val logoGone = binding.ivLogo.visibility == View.GONE
        val heightBudgetWithLogo = if (logoGone) heightBudget - logoBlockHeight() else heightBudget

        val hideLogo = heightBudgetWithLogo < requiredSize
        if (hideLogo == logoGone) return false
        L.i { "[ScreenLock] logo hidden=$hideLogo heightBudget=$heightBudgetWithLogo required=$requiredSize root=${root.width}x${root.height}" }
        binding.ivLogo.visibility = if (hideLogo) View.GONE else View.VISIBLE
        return true
    }

    /** Vertical space the header gives back when the logo is GONE (title falls back to its gone margin). */
    private fun logoBlockHeight(): Int {
        val logoLp = binding.ivLogo.layoutParams as ConstraintLayout.LayoutParams
        val titleLp = binding.tvTitle.layoutParams as ConstraintLayout.LayoutParams
        // Without an explicit gone margin ConstraintLayout keeps the regular one.
        val titleGoneMargin = titleLp.goneTopMargin
            .takeIf { it != ConstraintLayout.LayoutParams.GONE_UNSET } ?: titleLp.topMargin
        return logoLp.height + logoLp.topMargin + titleLp.topMargin - titleGoneMargin
    }
}
