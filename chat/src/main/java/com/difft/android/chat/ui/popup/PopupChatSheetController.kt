package com.difft.android.chat.ui.popup

import com.difft.android.base.utils.safeTopInset
import android.annotation.SuppressLint
import android.view.View
import android.animation.ValueAnimator
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import com.difft.android.base.widget.applySharedMaxWidth
import com.difft.android.base.widget.SheetScrim
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.widget.InsetAwareConstraintLayout
import com.difft.android.chat.ui.BubbleWidth
import com.difft.android.chat.util.ViewUtil
import com.google.android.material.bottomsheet.BottomSheetBehavior








class PopupSheetViews(
    val coordinatorRoot: View,
    val bottomSheet: View,
    val scrim: View,
    val dragHandle: View,
)









class PopupChatSheetController(
    private val activity: BaseActivity,
    private val views: PopupSheetViews,
) {

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var bottomSheetCallback: BottomSheetBehavior.BottomSheetCallback

    



    private val keyboardPanel = PopupKeyboardPanelController(activity, views.coordinatorRoot)

    private var isBottomSheetConfigured = false
    private var baseHeight = 0
    private var statusBarHeight = 0

    private var isMaximizing = false
    private var maximizeAnimator: ValueAnimator? = null

    fun setup() {
        
        views.scrim.setBackgroundColor(SheetScrim.argb(activity))
        
        ViewCompat.setOnApplyWindowInsetsListener(views.coordinatorRoot) { root, insets ->
            val navigationBarHeight =
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            statusBarHeight = root.safeTopInset(insets)
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            
            if (!isBottomSheetConfigured) {
                configureBehavior(navigationBarHeight)
                isBottomSheetConfigured = true
            }

            
            
            
            
            
            val maxHeightPx = (
                (views.coordinatorRoot.height.takeIf { it > 0 }
                    ?: WindowSizeClassUtil.getWindowHeightPx(activity)) - statusBarHeight
                ).coerceAtLeast(0)

            keyboardPanel.onWindowInsets(
                navigationBarPx = navigationBarHeight,
                imeHeightPx = imeHeight,
                
                
                
                imeVisible = isImeVisible,
                maxHeightPx = maxHeightPx,
            )

            insets
        }
        views.coordinatorRoot.requestApplyInsets()
    }

    private fun configureBehavior(navigationBarHeight: Int) {
        val bottomSheet = views.bottomSheet
        val scrim = views.scrim
        val screenHeight = WindowSizeClassUtil.getWindowHeightPx(activity)
        baseHeight = (screenHeight * 0.5).toInt()

        
        bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
            height = baseHeight
        }

        
        PopupSheetCard.apply(bottomSheet)
        PopupSheetCard.setBottomClearance(bottomSheet, navigationBarHeight)

        
        scrim.alpha = 0f

        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.apply {
            applySharedMaxWidth(activity.resources)
            peekHeight = baseHeight
            isFitToContents = true
            isHideable = true
            isDraggable = true  
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_HIDDEN  
        }

        
        
        keyboardPanel.attach(bottomSheet, bottomSheetBehavior, baseHeight)

        
        bottomSheet.post {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        
        scrim.setOnClickListener {
            cancelMaximize()
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }

        
        bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
            private var hasOpened = false  
            private var isClosing = false  
            private var keyboardDismissed = false  

            
            
            @SuppressLint("SwitchIntDef")
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        
                        if (!hasOpened) {
                            hasOpened = true
                            scrim.animate()
                                .alpha(0.5f)
                                .setDuration(150)
                                .start()
                        }
                        isClosing = false
                        keyboardDismissed = false
                        
                        
                        keyboardPanel.onSheetSettled()
                    }

                    BottomSheetBehavior.STATE_SETTLING -> {
                        
                        if (hasOpened) {
                            isClosing = true
                        }
                    }

                    BottomSheetBehavior.STATE_HIDDEN -> {
                        
                        
                        cancelMaximize()
                        activity.finish()
                        @Suppress("DEPRECATION")
                        activity.overridePendingTransition(0, 0)
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                
                
                if (hasOpened && slideOffset < 0 && !keyboardDismissed) {
                    activity.currentFocus?.let { ViewUtil.hideKeyboard(activity, it) }
                    keyboardDismissed = true
                }
                
                if (hasOpened && isClosing && slideOffset < 0) {
                    
                    scrim.alpha = ((slideOffset + 1f) * 0.5f).coerceIn(0f, 0.5f)
                }
            }
        }
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback)
    }

    






    fun dismiss(): Boolean {
        if (!::bottomSheetBehavior.isInitialized) return false
        cancelMaximize()
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        return true
    }

    
    private fun cancelMaximize() {
        maximizeAnimator?.cancel()
        maximizeAnimator = null
    }

    




    fun handOffToDetailPane(onEnd: () -> Unit) {
        if (isMaximizing) return
        isMaximizing = true
        onEnd()
    }

    





    fun maximize(onEnd: () -> Unit) {
        
        if (isMaximizing) return
        isMaximizing = true

        
        
        val hasKeyboard = keyboardPanel.isKeyboardVisible

        
        
        keyboardPanel.setMaximizeAnimating(true)

        
        val currentFocus = activity.currentFocus
        if (currentFocus != null) {
            ViewUtil.hideKeyboard(activity, currentFocus)
        }

        
        views.dragHandle.visibility = View.GONE

        
        val fullHeight = views.coordinatorRoot.height
        
        val targetHeight = fullHeight - statusBarHeight
        
        val currentHeight = views.bottomSheet.layoutParams.height
        
        val cardInset = activity.resources.getDimensionPixelSize(com.difft.android.base.R.dimen.bottom_sheet_inset)
        val initialPadding = if (hasKeyboard) (views.bottomSheet.paddingBottom - cardInset).coerceAtLeast(0) else 0
        
        
        val startWidth = views.bottomSheet.width
        val sideInsets = ViewCompat.getRootWindowInsets(views.coordinatorRoot)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val fullWidth = views.coordinatorRoot.width - (sideInsets?.left ?: 0) - (sideInsets?.right ?: 0)

        
        if (fullWidth != startWidth) BubbleWidth.freeze()
        
        maximizeAnimator = ValueAnimator.ofInt(currentHeight, targetHeight).apply {
            duration = 250
            addUpdateListener { animator ->
                val height = animator.animatedValue as Int
                if (fullWidth != startWidth) {
                    bottomSheetBehavior.maxWidth = startWidth + ((fullWidth - startWidth) * animator.animatedFraction).toInt()
                }
                views.bottomSheet.layoutParams = views.bottomSheet.layoutParams.apply {
                    this.height = height
                }
                
                if (hasKeyboard) {
                    val progress = (height - currentHeight).toFloat() / (targetHeight - currentHeight)
                    val newPadding = (initialPadding * (1 - progress)).toInt()
                    PopupSheetCard.setBottomClearance(views.bottomSheet, newPadding)
                }
                bottomSheetBehavior.peekHeight = height
            }
            var cancelled = false
            doOnCancel { cancelled = true }
            doOnEnd {
                maximizeAnimator = null
                BubbleWidth.unfreeze()
                
                
                if (!cancelled) onEnd()
            }
            start()
        }

        
        views.scrim.animate()
            .alpha(0f)
            .setDuration(250)
            .start()
    }

    fun release() {
        cancelMaximize()
        if (::bottomSheetBehavior.isInitialized && ::bottomSheetCallback.isInitialized) {
            bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallback)
        }
        keyboardPanel.release()
    }

    
    
    
    
    

    fun addKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener) {
        keyboardPanel.addKeyboardStateListener(listener)
    }

    fun removeKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener) {
        keyboardPanel.removeKeyboardStateListener(listener)
    }

    fun freezeKeyboardPadding() {
        keyboardPanel.freezeKeyboardPadding()
    }

    fun releaseKeyboardPaddingFreeze() {
        keyboardPanel.releaseKeyboardPaddingFreeze()
    }

    fun onChatPanelVisibilityChanged(visible: Boolean, panelHeightPx: Int) {
        keyboardPanel.onPanelVisibilityChanged(visible, panelHeightPx)
    }
}
