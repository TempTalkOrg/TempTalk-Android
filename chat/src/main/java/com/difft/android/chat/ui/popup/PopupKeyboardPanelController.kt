package com.difft.android.chat.ui.popup

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.view.updateLayoutParams
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.KeyboardHeightCache
import com.difft.android.base.widget.InsetAwareConstraintLayout
import com.difft.android.chat.ui.CHAT_PANEL_ANIM_DURATION_MS
import com.google.android.material.bottomsheet.BottomSheetBehavior







class PopupKeyboardPanelController(
    private val activity: BaseActivity,
    private val root: View,
) {

    
    private var sheet: View? = null
    private var behavior: BottomSheetBehavior<View>? = null
    private var baseHeightPx = 0
    private var maxHeightPx = 0
    private var navigationBarHeightPx = 0
    private var imeHeightPx = 0

    
    private var imeVisible = false
    private var panelHeightPx = 0
    private var panelVisible = false
    private var heldLiftPx = 0

    
    private var appliedHeightPx = 0
    private var appliedPaddingPx = -1
    private var pendingHeightPx: Int? = null
    private var heightAnimator: ValueAnimator? = null
    private var maximizeAnimating = false

    
    private var isKeyboardShown = false
    private var lastImeHeightPx = 0

    
    private var visibleStretchPeakPx = 0
    private var visibleStretchOrientation = Configuration.ORIENTATION_UNDEFINED

    private var pendingAnimationEnd: Runnable? = null
    private val keyboardStateListeners =
        mutableListOf<InsetAwareConstraintLayout.KeyboardStateListener>()

    
    val isKeyboardVisible: Boolean get() = imeVisible

    










    fun attach(sheet: View, behavior: BottomSheetBehavior<View>, baseHeightPx: Int) {
        this.sheet = sheet
        this.behavior = behavior
        this.baseHeightPx = baseHeightPx
        this.appliedHeightPx = baseHeightPx
    }

    



    fun onWindowInsets(
        navigationBarPx: Int,
        imeHeightPx: Int,
        imeVisible: Boolean,
        maxHeightPx: Int,
    ) {
        
        this.navigationBarHeightPx = navigationBarPx
        this.imeHeightPx = imeHeightPx
        this.imeVisible = imeVisible && imeHeightPx > 0
        this.maxHeightPx = maxHeightPx
        applyGeometry(animateHeight = false)

        
        
        dispatchImeEdges(imeVisible, imeHeightPx)

        
        persistKeyboardHeight()
    }

    




    fun onPanelVisibilityChanged(visible: Boolean, heightPx: Int) {
        if (visible && heightPx <= 0) {
            L.w { "[PopupSheet] panel visible with heightPx=$heightPx, no lift applied" }
        }
        panelVisible = visible
        panelHeightPx = if (visible) heightPx.coerceAtLeast(0) else 0
        applyGeometry(animateHeight = true)
    }

    



    fun freezeKeyboardPadding() {
        applyGeometry(animateHeight = false)
    }

    fun releaseKeyboardPaddingFreeze() {
        applyGeometry(animateHeight = false)
        sheet?.requestApplyInsets()
    }

    fun addKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener) {
        keyboardStateListeners += listener
    }

    fun removeKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener) {
        keyboardStateListeners -= listener
    }

    
    fun setMaximizeAnimating(animating: Boolean) {
        maximizeAnimating = animating
        if (animating) {
            heightAnimator?.cancel()
            heightAnimator = null
            pendingHeightPx = null
        }
    }

    
    fun onSheetSettled() {
        pendingHeightPx?.let {
            pendingHeightPx = null
            applyHeight(it, animate = false)
        }
    }

    




    fun release() {
        heightAnimator?.cancel()
        heightAnimator = null
        pendingAnimationEnd?.let { root.removeCallbacks(it) }
        pendingAnimationEnd = null
        keyboardStateListeners.clear()
        sheet = null
        behavior = null
    }

    

    private fun applyGeometry(animateHeight: Boolean) {
        if (maximizeAnimating || sheet == null || baseHeightPx <= 0) return
        val geometry = PopupSheetGeometry.compute(currentInput())
        if (geometry.heldLiftPx != heldLiftPx) {
            L.i {
                "[PopupSheet] lift ${heldLiftPx}->${geometry.heldLiftPx} " +
                    "ime=$imeHeightPx/$imeVisible panel=$panelHeightPx/$panelVisible"
            }
        }
        heldLiftPx = geometry.heldLiftPx
        applyPadding(geometry.paddingBottomPx)
        applyHeight(geometry.heightPx, animateHeight)
    }

    private fun currentInput() = SheetLiftInput(
        baseHeightPx = baseHeightPx,
        navigationBarHeightPx = navigationBarHeightPx,
        imeHeightPx = imeHeightPx,
        imeVisible = imeVisible,
        panelHeightPx = panelHeightPx,
        panelVisible = panelVisible,
        heldLiftPx = heldLiftPx,
        maxHeightPx = maxHeightPx,
    )

    private fun applyPadding(px: Int) {
        if (px == appliedPaddingPx) return
        appliedPaddingPx = px
        sheet?.let { PopupSheetCard.setBottomClearance(it, px) }
    }

    private fun applyHeight(targetPx: Int, animate: Boolean) {
        if (targetPx == appliedHeightPx) return
        val state = behavior?.state
        if (state == BottomSheetBehavior.STATE_DRAGGING || state == BottomSheetBehavior.STATE_SETTLING) {
            
            
            
            
            
            heightAnimator?.cancel()
            heightAnimator = null
            pendingHeightPx = targetPx
            return
        }
        heightAnimator?.cancel()
        if (!animate) {
            setHeight(targetPx)
            return
        }
        val from = appliedHeightPx
        heightAnimator = ValueAnimator.ofInt(from, targetPx).apply {
            
            
            duration = CHAT_PANEL_ANIM_DURATION_MS
            interpolator = if (targetPx > from) DecelerateInterpolator() else AccelerateInterpolator()
            addUpdateListener { setHeight(it.animatedValue as Int) }
            start()
        }
    }

    private fun setHeight(px: Int) {
        appliedHeightPx = px
        sheet?.updateLayoutParams { height = px }
        behavior?.peekHeight = px
    }

    

    

    private enum class Edge { SHOWN, HIDDEN, HEIGHT_ONLY, NONE }

    




    private fun dispatchImeEdges(imeVisible: Boolean, imeHeightPx: Int) {
        val edge = when {
            imeVisible && !isKeyboardShown -> { isKeyboardShown = true; Edge.SHOWN }
            !imeVisible && isKeyboardShown -> { isKeyboardShown = false; visibleStretchPeakPx = 0; Edge.HIDDEN }
            imeHeightPx != lastImeHeightPx -> Edge.HEIGHT_ONLY
            else -> Edge.NONE 
        }
        if (edge == Edge.NONE) return

        lastImeHeightPx = imeHeightPx
        L.i { "[ChatPopupKeyboard] ime edge=$edge visible=$imeVisible height=$imeHeightPx" }

        when (edge) {
            Edge.SHOWN -> postDispatch { it.onKeyboardShown() }
            Edge.HIDDEN -> postDispatch { it.onKeyboardHidden() }
            
            
            else -> Unit
        }
        scheduleAnimationEnd(imeVisible)
    }

    




    private fun scheduleAnimationEnd(visible: Boolean) {
        pendingAnimationEnd?.let { root.removeCallbacks(it) }
        val runnable = Runnable {
            pendingAnimationEnd = null
            dispatchNow { it.onKeyboardAnimationEnded(visible) }
        }
        pendingAnimationEnd = runnable
        root.post(runnable)
    }

    




    private fun dispatchNow(action: (InsetAwareConstraintLayout.KeyboardStateListener) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed || !root.isAttachedToWindow) {
            L.w { "[ChatPopupKeyboard] dispatch dropped: host not alive" }
            return
        }
        keyboardStateListeners.toList().forEach(action)
    }

    private fun postDispatch(action: (InsetAwareConstraintLayout.KeyboardStateListener) -> Unit) {
        root.post { dispatchNow(action) }
    }

    

    

    








    private fun persistKeyboardHeight() {
        if (!imeVisible) return
        val orientation = root.context.resources.configuration.orientation
        if (orientation != visibleStretchOrientation) {
            visibleStretchOrientation = orientation
            visibleStretchPeakPx = 0
        }
        val keyboardHeight = imeHeightPx - navigationBarHeightPx
        if (keyboardHeight < visibleStretchPeakPx) return
        visibleStretchPeakPx = keyboardHeight
        
        KeyboardHeightCache.save(root.context, keyboardHeight)
    }

    
}
