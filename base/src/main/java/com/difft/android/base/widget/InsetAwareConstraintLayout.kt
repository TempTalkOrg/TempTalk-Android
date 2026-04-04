package com.difft.android.base.widget

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.difft.android.base.R
import com.difft.android.base.utils.SharedPrefsUtil

/**
 * A ConstraintLayout that automatically manages system window insets (status bar, navigation bar,
 * keyboard) via bottom padding and optional guidelines.
 *
 * Keyboard offset is applied as bottom padding so the entire constraint area shrinks together,
 * ensuring all children (message list, input, etc.) move in sync.
 *
 * Supports smooth keyboard animation via [WindowInsetsAnimationCompat.Callback] when
 * [R.styleable.InsetAwareConstraintLayout_animateKeyboardChanges] is enabled.
 *
 * Use [setUseWindowTypes] to control whether system bars insets are applied (disable for
 * dual-pane mode where the host Activity already handles system bars).
 */
class InsetAwareConstraintLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    interface KeyboardStateListener {
        fun onKeyboardShown() {}
        fun onKeyboardHidden() {}
        fun onKeyboardAnimationEnded(isKeyboardVisible: Boolean) {}
    }

    private var statusBarGuideline: Guideline? = null
    private var navigationBarGuideline: Guideline? = null
    private var keyboardGuideline: Guideline? = null
    private var parentStartGuideline: Guideline? = null
    private var parentEndGuideline: Guideline? = null

    private val keyboardStateListeners = mutableListOf<KeyboardStateListener>()
    private var useWindowTypes = true
    private var animateKeyboardChanges = false
    private var isKeyboardVisible = false
    private var isKeyboardAnimating = false
    private var isKeyboardPaddingFrozen = false
    private var pendingKeyboardBottom: Int? = null
    private var lastKeyboardHeight = 0
    private var lastNavigationBarBottom = 0

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.InsetAwareConstraintLayout)
            animateKeyboardChanges = a.getBoolean(
                R.styleable.InsetAwareConstraintLayout_animateKeyboardChanges, false
            )
            a.recycle()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        statusBarGuideline = findViewById(R.id.status_bar_guideline)
        navigationBarGuideline = findViewById(R.id.navigation_bar_guideline)
        keyboardGuideline = findViewById(R.id.keyboard_guideline)
        parentStartGuideline = findViewById(R.id.parent_start_guideline)
        parentEndGuideline = findViewById(R.id.parent_end_guideline)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applyInsets(insets)
            insets
        }

        // WindowInsetsAnimationCompat only works reliably on API 30+.
        // On older APIs, the compat fallback relies on window resize to detect IME,
        // which doesn't work with adjustNothing. Fall back to instant setPadding via applyInsets.
        if (animateKeyboardChanges && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            ViewCompat.setWindowInsetsAnimationCallback(this, KeyboardInsetAnimator())
        }

        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        ViewCompat.setWindowInsetsAnimationCallback(this, null)
    }

    /**
     * Control whether system bars (status bar, navigation bar, side insets) are applied.
     * Set to false in dual-pane mode where the host Activity already handles system bars.
     * Keyboard padding is always updated regardless of this setting.
     */
    fun setUseWindowTypes(use: Boolean) {
        if (useWindowTypes != use) {
            useWindowTypes = use
            requestApplyInsets()
        }
    }

    fun addKeyboardStateListener(listener: KeyboardStateListener) {
        keyboardStateListeners.add(listener)
    }

    fun removeKeyboardStateListener(listener: KeyboardStateListener) {
        keyboardStateListeners.remove(listener)
    }

    /**
     * Freeze keyboard padding and immediately set it to the non-IME value (navigation bar only).
     * Use when switching from keyboard to a custom panel — the panel provides the height that
     * the keyboard was occupying, so the layout stays in place while the keyboard closes behind
     * the scenes.
     *
     * Call [releaseKeyboardPaddingFreeze] when switching back to keyboard or closing the panel.
     */
    fun freezeKeyboardPadding() {
        isKeyboardPaddingFrozen = true
        val navBottom = if (useWindowTypes) lastNavigationBarBottom else 0
        setPadding(paddingLeft, paddingTop, paddingRight, navBottom)
    }

    /**
     * Release the keyboard padding freeze. Immediately applies the pending padding value
     * to avoid a 1-frame flicker when hiding a panel simultaneously.
     */
    fun releaseKeyboardPaddingFreeze() {
        isKeyboardPaddingFrozen = false
        pendingKeyboardBottom?.let {
            setPadding(paddingLeft, paddingTop, paddingRight, it)
        }
        pendingKeyboardBottom = null
        requestApplyInsets()
    }

    private fun applyInsets(insets: WindowInsetsCompat) {
        val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
        val navigationBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
        val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

        lastNavigationBarBottom = navigationBarInsets.bottom

        if (useWindowTypes) {
            statusBarGuideline?.setGuidelineBegin(statusBarInsets.top)
            navigationBarGuideline?.setGuidelineEnd(navigationBarInsets.bottom)

            val isLtr = layoutDirection == LAYOUT_DIRECTION_LTR
            parentStartGuideline?.setGuidelineBegin(
                if (isLtr) navigationBarInsets.left else navigationBarInsets.right
            )
            parentEndGuideline?.setGuidelineEnd(
                if (isLtr) navigationBarInsets.right else navigationBarInsets.left
            )
        }

        // Keyboard offset applied as bottom padding. During animation, onProgress handles it.
        // When frozen (switching to custom panel), skip padding updates.
        // When useWindowTypes=false (dual-pane), subtract nav bar to avoid double padding.
        val keyboardBottom = if (useWindowTypes) {
            if (isImeVisible) imeInsets.bottom else navigationBarInsets.bottom
        } else {
            if (isImeVisible) maxOf(0, imeInsets.bottom - navigationBarInsets.bottom) else 0
        }
        if (isKeyboardPaddingFrozen) {
            pendingKeyboardBottom = keyboardBottom
        } else if (!animateKeyboardChanges || !isKeyboardAnimating) {
            setPadding(paddingLeft, paddingTop, paddingRight, keyboardBottom)
        }
        keyboardGuideline?.setGuidelineEnd(keyboardBottom)

        if (isImeVisible && !isKeyboardVisible) {
            isKeyboardVisible = true
            keyboardStateListeners.forEach { it.onKeyboardShown() }
        } else if (!isImeVisible && isKeyboardVisible) {
            isKeyboardVisible = false
            keyboardStateListeners.forEach { it.onKeyboardHidden() }
        }

        if (isImeVisible && imeInsets.bottom > 0) {
            val keyboardHeight = imeInsets.bottom - navigationBarInsets.bottom
            if (keyboardHeight > 0 && keyboardHeight != lastKeyboardHeight) {
                lastKeyboardHeight = keyboardHeight
                saveKeyboardHeight(keyboardHeight)
            }
        }
    }

    /**
     * Animates keyboard padding frame-by-frame.
     *
     * [isKeyboardAnimating] is set in [onPrepare] because the system dispatches final insets
     * between onPrepare and onStart — without this, [applyInsets] would jump to the final value.
     *
     * [onProgress] uses insets directly (no manual interpolation) because the system already
     * provides interpolated values at each frame.
     */
    private inner class KeyboardInsetAnimator : WindowInsetsAnimationCompat.Callback(
        DISPATCH_MODE_STOP
    ) {
        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
            super.onPrepare(animation)
            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                isKeyboardAnimating = true
            }
        }

        override fun onStart(
            animation: WindowInsetsAnimationCompat,
            bounds: WindowInsetsAnimationCompat.BoundsCompat
        ): WindowInsetsAnimationCompat.BoundsCompat = bounds

        override fun onProgress(
            insets: WindowInsetsCompat,
            runningAnimations: MutableList<WindowInsetsAnimationCompat>
        ): WindowInsetsCompat {
            if (isKeyboardPaddingFrozen) return insets
            if (runningAnimations.none { it.typeMask and WindowInsetsCompat.Type.ime() != 0 }) {
                return insets
            }

            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val bottom = if (useWindowTypes) {
                maxOf(imeInsets.bottom, navInsets.bottom)
            } else {
                maxOf(0, imeInsets.bottom - navInsets.bottom)
            }
            setPadding(paddingLeft, paddingTop, paddingRight, bottom)

            return insets
        }

        override fun onEnd(animation: WindowInsetsAnimationCompat) {
            super.onEnd(animation)
            if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                isKeyboardAnimating = false
                requestApplyInsets()
                keyboardStateListeners.forEach { it.onKeyboardAnimationEnded(isKeyboardVisible) }
            }
        }
    }

    private fun saveKeyboardHeight(height: Int) {
        val key = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            SP_KEY_KEYBOARD_HEIGHT_LANDSCAPE
        } else {
            SP_KEY_KEYBOARD_HEIGHT_PORTRAIT
        }
        SharedPrefsUtil.putInt(key, height)
    }

    companion object {
        private const val SP_KEY_KEYBOARD_HEIGHT_PORTRAIT = "keyboard_height_portrait"
        private const val SP_KEY_KEYBOARD_HEIGHT_LANDSCAPE = "keyboard_height_landscape"

        fun getKeyboardHeight(context: Context): Int {
            val key = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                SP_KEY_KEYBOARD_HEIGHT_LANDSCAPE
            } else {
                SP_KEY_KEYBOARD_HEIGHT_PORTRAIT
            }
            return SharedPrefsUtil.getInt(key, 0)
        }
    }
}
