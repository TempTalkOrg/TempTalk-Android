package com.difft.android.chat.ui.popup

import android.annotation.SuppressLint
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.widget.InsetAwareConstraintLayout
import com.difft.android.chat.util.ViewUtil
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * The four sheet views, bridged from either popup layout's generated binding.
 *
 * Both popup layouts declare the same ids (`coordinator_root` / `scrim` / `bottom_sheet` /
 * `drag_handle`), but their generated binding classes have no common supertype, so this bundle
 * is what lets one controller serve both without inheritance.
 */
class PopupSheetViews(
    val coordinatorRoot: View,
    val bottomSheet: View,
    val scrim: View,
    val dragHandle: View,
)

/**
 * Owns the popup bottom-sheet lifecycle shared by `ChatPopupActivity` and
 * `GroupChatPopupActivity`: window-insets handling, [BottomSheetBehavior] configuration, the
 * scrim fade animations, drag-to-dismiss, and the maximize-to-full-screen animation.
 *
 * The only per-Activity difference is which full-screen Activity to start once the maximize
 * animation ends; that is supplied by the caller as [maximize]'s `onEnd` lambda.
 */
class PopupChatSheetController(
    private val activity: BaseActivity,
    private val views: PopupSheetViews,
) {

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var bottomSheetCallback: BottomSheetBehavior.BottomSheetCallback

    /**
     * Single owner of the sheet's height / bottom padding / peek height, and the source of the
     * keyboard-state callbacks the chat input fragment registers for.
     */
    private val keyboardPanel = PopupKeyboardPanelController(activity, views.coordinatorRoot)

    private var isBottomSheetConfigured = false
    private var baseHeight = 0
    private var statusBarHeight = 0

    private var isMaximizing = false

    fun setup() {
        // Handle window insets for navigation bar and IME (keyboard)
        ViewCompat.setOnApplyWindowInsetsListener(views.coordinatorRoot) { _, insets ->
            val navigationBarHeight =
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            // Configure bottom sheet behavior only once (to get base height)
            if (!isBottomSheetConfigured) {
                configureBehavior(navigationBarHeight)
                isBottomSheetConfigured = true
            }

            // Recomputed on every pass. The first pass runs before the root is laid out, so a
            // once-only computation would freeze the ceiling at a value taken from a root with no
            // height and never track the real window afterwards. The ceiling matters because
            // isFitToContents pins an over-tall sheet's top edge at 0 and pushes its own input row
            // off the bottom of the screen.
            val maxHeightPx = (
                (views.coordinatorRoot.height.takeIf { it > 0 }
                    ?: WindowSizeClassUtil.getWindowHeightPx(activity)) - statusBarHeight
                ).coerceAtLeast(0)

            keyboardPanel.onWindowInsets(
                navigationBarPx = navigationBarHeight,
                imeHeightPx = imeHeight,
                // RAW visibility. The controller folds `imeHeight > 0` in for GEOMETRY only, so a
                // zero-height IME still takes the resting branch, while the keyboard-shown/hidden
                // edges stay on the raw flag and match the full-screen path.
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

        // Set fixed height for bottom sheet
        bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
            height = baseHeight
        }

        // Apply navigation bar padding initially
        bottomSheet.setPadding(0, 0, 0, navigationBarHeight)

        // Start scrim transparent, will fade in with bottom sheet animation
        scrim.alpha = 0f

        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.apply {
            peekHeight = baseHeight
            isFitToContents = true
            isHideable = true
            isDraggable = true  // Enable drag to dismiss
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_HIDDEN  // Start hidden for animation
        }

        // The height and padding written just above are the baseline the controller records as
        // already applied, so its first geometry pass writes no height.
        keyboardPanel.attach(bottomSheet, bottomSheetBehavior, baseHeight)

        // Show bottom sheet after layout is complete
        bottomSheet.post {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        // Scrim click handler - close popup
        scrim.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }

        // Bottom sheet callback - handle state changes and scrim animation
        bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
            private var hasOpened = false  // Track if bottom sheet has finished opening
            private var isClosing = false  // Track if bottom sheet is being closed
            private var keyboardDismissed = false  // Track if keyboard has been dismissed during close

            // Intentional partial coverage — only EXPANDED/SETTLING/HIDDEN drive UI state.
            // COLLAPSED/DRAGGING/HALF_EXPANDED are no-ops for this dialog (no half-expanded peek).
            @SuppressLint("SwitchIntDef")
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        // Fade in scrim after bottom sheet is fully visible (first time only)
                        if (!hasOpened) {
                            hasOpened = true
                            scrim.animate()
                                .alpha(0.5f)
                                .setDuration(150)
                                .start()
                        }
                        isClosing = false
                        keyboardDismissed = false
                        // The sheet is at rest again: replay any height write deferred while it
                        // was being dragged or settling.
                        keyboardPanel.onSheetSettled()
                    }

                    BottomSheetBehavior.STATE_SETTLING -> {
                        // If already opened and now settling, it means closing
                        if (hasOpened) {
                            isClosing = true
                        }
                    }

                    BottomSheetBehavior.STATE_HIDDEN -> {
                        activity.finish()
                        @Suppress("DEPRECATION")
                        activity.overridePendingTransition(0, 0)
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // slideOffset: -1 (hidden) to 0 (expanded)
                // Hide keyboard when starting to close (covers all close scenarios)
                if (hasOpened && slideOffset < 0 && !keyboardDismissed) {
                    activity.currentFocus?.let { ViewUtil.hideKeyboard(activity, it) }
                    keyboardDismissed = true
                }
                // Fade scrim when closing
                if (hasOpened && isClosing && slideOffset < 0) {
                    // Map -1..0 to 0..0.5
                    scrim.alpha = ((slideOffset + 1f) * 0.5f).coerceIn(0f, 0.5f)
                }
            }
        }
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback)
    }

    /**
     * Hides the sheet if the behavior has already been configured.
     *
     * @return `true` when the sheet took the dismissal (it animates to `STATE_HIDDEN`, whose
     * callback finishes the Activity); `false` when the behavior is not initialised yet, so the
     * caller must finish the Activity itself.
     */
    fun dismiss(): Boolean {
        if (!::bottomSheetBehavior.isInitialized) return false
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        return true
    }

    /**
     * Animates the bottom sheet to full screen height, then invokes [onEnd].
     *
     * [onEnd] carries the only per-Activity divergence: which full-screen Activity to start and
     * with which extras. Double taps are guarded, so [onEnd] runs at most once.
     */
    fun maximize(onEnd: () -> Unit) {
        // Prevent double-click
        if (isMaximizing) return
        isMaximizing = true

        // Read the real IME state BEFORE hiding the keyboard: hideKeyboard() can be followed by an
        // IME-hidden insets pass at any moment, so a read afterwards races it.
        val hasKeyboard = keyboardPanel.isKeyboardVisible

        // Take exclusive ownership of height / padding / peekHeight for the animation below, and
        // do it before hideKeyboard() so no insets pass can apply geometry in between.
        keyboardPanel.setMaximizeAnimating(true)

        // Hide keyboard first if visible
        val currentFocus = activity.currentFocus
        if (currentFocus != null) {
            ViewUtil.hideKeyboard(activity, currentFocus)
        }

        // Hide drag handle so header aligns to top
        views.dragHandle.visibility = View.GONE

        // Use the actual laid out height of coordinatorRoot which spans the full screen in edge-to-edge mode
        val fullHeight = views.coordinatorRoot.height
        // Target height stops at status bar bottom (aligns with the full Activity's title bar position)
        val targetHeight = fullHeight - statusBarHeight
        // Start from current height (which may include keyboard or panel height)
        val currentHeight = views.bottomSheet.layoutParams.height
        // Capture initial padding to animate it out smoothly (only needed when keyboard is visible)
        val initialPadding = if (hasKeyboard) views.bottomSheet.paddingBottom else 0

        // Animate bottom sheet to target height (just below status bar)
        android.animation.ValueAnimator.ofInt(currentHeight, targetHeight).apply {
            duration = 250
            addUpdateListener { animator ->
                val height = animator.animatedValue as Int
                views.bottomSheet.layoutParams = views.bottomSheet.layoutParams.apply {
                    this.height = height
                }
                // Gradually reduce bottom padding as we expand (only when keyboard was visible)
                if (hasKeyboard) {
                    val progress = (height - currentHeight).toFloat() / (targetHeight - currentHeight)
                    val newPadding = (initialPadding * (1 - progress)).toInt()
                    views.bottomSheet.setPadding(0, 0, 0, newPadding)
                }
                bottomSheetBehavior.peekHeight = height
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Start the full Activity after animation completes
                    onEnd()
                }
            })
            start()
        }

        // Fade out scrim simultaneously
        views.scrim.animate()
            .alpha(0f)
            .setDuration(250)
            .start()
    }

    fun release() {
        if (::bottomSheetBehavior.isInitialized && ::bottomSheetCallback.isInitialized) {
            bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallback)
        }
        keyboardPanel.release()
    }

    // --- KeyboardPanelHost forwarding ------------------------------------------------------
    // The popup Activities implement KeyboardPanelHost and forward every method here, so the
    // chat input fragment's keyboard/panel handshake reaches the single owner of the sheet's
    // geometry. Kept as explicit forwarding rather than interface delegation because the
    // Activities construct this controller lazily (it reads mBinding).

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
