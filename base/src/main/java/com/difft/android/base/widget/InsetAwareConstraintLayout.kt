package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.difft.android.base.R
import com.difft.android.base.storage.KeyboardHeightCache














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
    private var lastNavigationBarBottom = 0

    init {
        attrs?.let {
            context.withStyledAttributes(it, R.styleable.InsetAwareConstraintLayout) {
                animateKeyboardChanges = getBoolean(
                    R.styleable.InsetAwareConstraintLayout_animateKeyboardChanges, false
                )
            }
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

    



    private fun dispatchKeyboardListeners(action: (KeyboardStateListener) -> Unit) {
        val snapshot = keyboardStateListeners.toList()
        post {
            if (!isAttachedToWindow) return@post
            snapshot.forEach(action)
        }
    }

    







    fun freezeKeyboardPadding() {
        isKeyboardPaddingFrozen = true
        val navBottom = if (useWindowTypes) lastNavigationBarBottom else 0
        setPadding(paddingLeft, paddingTop, paddingRight, navBottom)
    }

    



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
            dispatchKeyboardListeners { it.onKeyboardShown() }
        } else if (!isImeVisible && isKeyboardVisible) {
            isKeyboardVisible = false
            dispatchKeyboardListeners { it.onKeyboardHidden() }
        }

        
        
        
        
        if (isImeVisible && imeInsets.bottom > 0 && !isKeyboardAnimating) {
            KeyboardHeightCache.save(context, imeInsets.bottom - navigationBarInsets.bottom)
        }
    }

    








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
                
                val visibleAtAnimationEnd = isKeyboardVisible
                requestApplyInsets()
                dispatchKeyboardListeners { it.onKeyboardAnimationEnded(visibleAtAnimationEnd) }
            }
        }
    }

    companion object {
        
        fun getKeyboardHeight(context: Context): Int = KeyboardHeightCache.get(context)
    }
}
