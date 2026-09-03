package com.difft.android.chat.ui.popup

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.view.updateLayoutParams
import androidx.datastore.preferences.core.edit
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateDataStoreEntryPoint
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.utils.appScope
import com.difft.android.base.widget.InsetAwareConstraintLayout
import com.difft.android.chat.ui.CHAT_PANEL_ANIM_DURATION_MS
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Owns the popup chat sheet's keyboard/action-panel coordination: it holds the lift inputs, is the
 * single writer of the sheet's height / bottom padding / peek height, and synthesises the
 * keyboard-state callbacks the input fragment expects.
 *
 * Geometry and IME-edge dispatch live in ONE class on purpose: both read `imeVisible` /
 * `imeHeightPx` / `panelVisible` / `panelHeightPx`, so splitting them would create two sources of
 * truth for IME visibility plus a synchronisation contract between them — the exact defect class
 * this change fixes.
 *
 * Callbacks are synthesised from the popup's existing `OnApplyWindowInsetsListener`, NOT from a
 * `WindowInsetsAnimationCompat.Callback`: registering an animation callback changes how the
 * framework delivers regular insets during an IME animation (which would alter the popup's already
 * working keyboard lift), and the AndroidX backport does not cover API 26-29, where
 * `onKeyboardAnimationEnded` is the callback that dismisses the panel.
 *
 * Main-thread only, for the lifetime of the owning Activity. [release] must be called from
 * `onDestroy`.
 */
class PopupKeyboardPanelController(
    private val activity: BaseActivity,
    private val root: View,
) {

    // --- geometry inputs ---
    private var sheet: View? = null
    private var behavior: BottomSheetBehavior<View>? = null
    private var baseHeightPx = 0
    private var maxHeightPx = 0
    private var navigationBarHeightPx = 0
    private var imeHeightPx = 0

    /** Height-folded IME visibility (`raw && imeHeightPx > 0`); geometry only. See [onWindowInsets]. */
    private var imeVisible = false
    private var panelHeightPx = 0
    private var panelVisible = false
    private var heldLiftPx = 0

    // --- applied-state write guards ---
    private var appliedHeightPx = 0
    private var appliedPaddingPx = -1
    private var pendingHeightPx: Int? = null
    private var heightAnimator: ValueAnimator? = null
    private var maximizeAnimating = false

    // --- dispatch state (mirrors InsetAwareConstraintLayout's keyboard-visibility latch) ---
    private var isKeyboardShown = false
    private var lastImeHeightPx = 0

    /** Last value handed to [saveKeyboardHeight]; mirrors InsetAwareConstraintLayout's guard. */
    private var lastSavedKeyboardHeight = 0
    private var pendingAnimationEnd: Runnable? = null
    private val keyboardStateListeners =
        mutableListOf<InsetAwareConstraintLayout.KeyboardStateListener>()

    /**
     * Real IME visibility from the insets, height-folded. Replaces maximize's
     * `currentHeight > baseHeight` proxy. Folded is the right reading for its one caller: maximize
     * uses it to decide whether to ramp the IME padding back out, and a zero-height IME consumed no
     * padding to ramp.
     */
    val isKeyboardVisible: Boolean get() = imeVisible

    /**
     * Bind the sheet being lifted. The caller has just written `layoutParams.height = baseHeightPx`,
     * so that value is recorded as already applied and the first geometry pass does not rewrite an
     * identical height. [appliedPaddingPx] deliberately stays at `-1`, so the first pass always
     * writes padding once.
     *
     * Call this **once** per controller instance. The owning sheet controller calls it from behind
     * its one-shot `isBottomSheetConfigured` latch, and the controller is scoped to a single
     * Activity that cannot be recreated by a configuration change, so a second attach — in
     * particular an attach after [release] — is not a supported state. See [release].
     */
    fun attach(sheet: View, behavior: BottomSheetBehavior<View>, baseHeightPx: Int) {
        this.sheet = sheet
        this.behavior = behavior
        this.baseHeightPx = baseHeightPx
        this.appliedHeightPx = baseHeightPx
    }

    /**
     * @param imeVisible RAW `insets.isVisible(ime())`. Geometry and dispatch deliberately read it
     * differently — see the two steps below.
     */
    fun onWindowInsets(
        navigationBarPx: Int,
        imeHeightPx: Int,
        imeVisible: Boolean,
        maxHeightPx: Int,
    ) {
        // 1. record state + apply geometry — gated by maximizeAnimating, exactly as before.
        // The height is folded into visibility HERE, for geometry only: an IME reported visible with
        // a zero height has no pixels to lift or to consume as padding, so it must take the resting
        // branch rather than lift the sheet by 0 and drop the navigation-bar padding.
        this.navigationBarHeightPx = navigationBarPx
        this.imeHeightPx = imeHeightPx
        this.imeVisible = imeVisible && imeHeightPx > 0
        this.maxHeightPx = maxHeightPx
        applyGeometry(animateHeight = false)

        // 2. edge-detect + dispatch — deliberately NOT gated, see dispatchImeEdges, and deliberately
        // on the RAW visibility, NOT the folded one. InsetAwareConstraintLayout (the full-screen
        // path) latches onKeyboardShown/onKeyboardHidden on raw isVisible(ime()); folding the height
        // in here too would make the popup silent on the frames where the system reports the IME
        // visible with bottom=0 (floating/split keyboards, transient frames), so a full-screen chat
        // would dismiss its open action panel and the popup would leave it stacked behind the
        // keyboard — the exact defect class this controller exists to fix.
        dispatchImeEdges(imeVisible, imeHeightPx)

        // 3. seed the shared keyboard-height cache — see persistKeyboardHeight.
        persistKeyboardHeight()
    }

    /**
     * The action panel became visible/hidden. Applies SYNCHRONOUSLY: the fragment reports this at
     * the start of the panel's own animation, inside the click handler, so the sheet must grow in
     * the same frame rather than one post later.
     */
    fun onPanelVisibilityChanged(visible: Boolean, heightPx: Int) {
        if (visible && heightPx <= 0) {
            L.w { "[PopupSheet] panel visible with heightPx=$heightPx, no lift applied" }
        }
        panelVisible = visible
        panelHeightPx = if (visible) heightPx.coerceAtLeast(0) else 0
        applyGeometry(animateHeight = true)
    }

    /**
     * Idempotent recompute hooks. The authoritative input is `panelVisible`, not a freeze latch, so
     * these cannot leave the padding stuck when a close path forgets to release.
     */
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

    /** The maximize animator takes exclusive ownership of height / padding / peekHeight. */
    fun setMaximizeAnimating(animating: Boolean) {
        maximizeAnimating = animating
        if (animating) {
            heightAnimator?.cancel()
            heightAnimator = null
            pendingHeightPx = null
        }
    }

    /** Re-apply a height write deferred while the sheet was dragging or settling. */
    fun onSheetSettled() {
        pendingHeightPx?.let {
            pendingHeightPx = null
            applyHeight(it, animate = false)
        }
    }

    /**
     * Terminal teardown, called from the Activity's `onDestroy`. Everything that could outlive the
     * window is dropped: the height animator, the pending animation-end runnable, the listener list
     * and the view references.
     *
     * The applied-state guards ([appliedPaddingPx], [appliedHeightPx], [heldLiftPx]) and the
     * keyboard latch are deliberately NOT reset, because this is terminal: nulling [sheet] makes
     * every later geometry pass a no-op, and [attach] is never called again on a released instance
     * (the popup Activities declare `configChanges` for orientation, so no recreation occurs).
     * Resetting them would only matter for a re-attach that the contract does not allow, and would
     * make the stale values look like supported state.
     */
    fun release() {
        heightAnimator?.cancel()
        heightAnimator = null
        pendingAnimationEnd?.let { root.removeCallbacks(it) }
        pendingAnimationEnd = null
        keyboardStateListeners.clear()
        sheet = null
        behavior = null
    }

    // region geometry

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
        sheet?.setPadding(0, 0, 0, px)
    }

    private fun applyHeight(targetPx: Int, animate: Boolean) {
        if (targetPx == appliedHeightPx) return
        val state = behavior?.state
        if (state == BottomSheetBehavior.STATE_DRAGGING || state == BottomSheetBehavior.STATE_SETTLING) {
            // A height write repositions the sheet mid-gesture; re-applied from onSheetSettled().
            // An in-flight panel animation must be cancelled here, not merely deferred around: its
            // update listener writes heights directly, so leaving it running would keep resizing
            // the sheet under the user's finger AND would overwrite the deferred value replayed by
            // onSheetSettled() with frames aimed at a target geometry has already superseded.
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
            // Same duration and per-direction interpolator as the panel's own show/hide animation,
            // so the sheet's top edge and the panel height move on one curve.
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

    // endregion

    // region dispatch

    private enum class Edge { SHOWN, HIDDEN, HEIGHT_ONLY, NONE }

    /**
     * Dispatch is deliberately NOT gated by [maximizeAnimating] while geometry is: maximize hides
     * the keyboard BEFORE setting the flag, so the resulting IME-hidden insets arrive afterwards and
     * gating would strand [isKeyboardShown] at true. The callbacks are inert on that path.
     */
    private fun dispatchImeEdges(imeVisible: Boolean, imeHeightPx: Int) {
        val edge = when {
            imeVisible && !isKeyboardShown -> { isKeyboardShown = true; Edge.SHOWN }
            !imeVisible && isKeyboardShown -> { isKeyboardShown = false; Edge.HIDDEN }
            imeHeightPx != lastImeHeightPx -> Edge.HEIGHT_ONLY
            else -> Edge.NONE // nav-bar / status-bar only dispatch
        }
        if (edge == Edge.NONE) return

        lastImeHeightPx = imeHeightPx
        L.i { "[ChatPopupKeyboard] ime edge=$edge visible=$imeVisible height=$imeHeightPx" }

        when (edge) {
            Edge.SHOWN -> postDispatch { it.onKeyboardShown() }
            Edge.HIDDEN -> postDispatch { it.onKeyboardHidden() }
            // HEIGHT_ONLY reports no visibility change, but it IS an IME movement, so it re-arms the
            // animation-end runnable below — that is what makes interpolated insets settle correctly.
            else -> Unit
        }
        scheduleAnimationEnd(imeVisible)
    }

    /**
     * "Animation ended" == one frame after the LAST IME change of this transition. Re-arming on every
     * change means a device that delivers interpolated IME insets settles correctly with no
     * hard-coded animation duration.
     */
    private fun scheduleAnimationEnd(visible: Boolean) {
        pendingAnimationEnd?.let { root.removeCallbacks(it) }
        val runnable = Runnable {
            pendingAnimationEnd = null
            dispatchNow { it.onKeyboardAnimationEnded(visible) }
        }
        pendingAnimationEnd = runnable
        root.post(runnable)
    }

    /**
     * Runs [action] against a snapshot of the listeners, but only while the host window is alive.
     *
     * Listener bodies mutate the view tree (they hide the action panel), which must never happen
     * inside an `onApplyWindowInsets` callback — see Crashlytics issue 32a90db4. The snapshot also
     * makes re-entrant registration/removal safe. The Activity check is popup-specific: the sheet
     * calls `finish()` from `STATE_HIDDEN` and hides the keyboard from `onSlide`, so an IME-hidden
     * edge is guaranteed on every dismissal and its posted dispatch can land in a finishing Activity.
     */
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

    // endregion

    // region keyboard-height cache

    /**
     * Records the measured keyboard height into the app-state DataStore, mirroring
     * [InsetAwareConstraintLayout]'s own persistence (its `applyInsets` → `saveKeyboardHeight`).
     *
     * The popup path never goes through [InsetAwareConstraintLayout], so without this write an
     * install whose keyboard has only ever been shown inside popup chat leaves
     * [InsetAwareConstraintLayout.getKeyboardHeight] at 0. The chat input fragment then has no
     * height to give the action panel and falls back to wrap-content plus a fixed lift, which is
     * visible as a panel that does not match the keyboard and jumps when the two swap.
     *
     * The height must be the IME inset minus the navigation bar, matching the full-screen
     * computation exactly — the two paths write the same key and must agree.
     */
    private fun persistKeyboardHeight() {
        if (!imeVisible) return
        val keyboardHeight = imeHeightPx - navigationBarHeightPx
        if (keyboardHeight <= 0 || keyboardHeight == lastSavedKeyboardHeight) return
        lastSavedKeyboardHeight = keyboardHeight
        saveKeyboardHeight(keyboardHeight)
    }

    private fun saveKeyboardHeight(height: Int) {
        val key = if (root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE
        } else {
            AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT
        }
        val appContext = root.context.applicationContext
        // The entry-point lookup is resolved off the main thread, inside the same runCatching as the
        // write: this runs from an onApplyWindowInsets pass, so neither the Hilt lookup nor a graph
        // that is unavailable (tests, teardown) may block or break the insets callback.
        appScope.launch(Dispatchers.IO) {
            runCatching {
                EntryPointAccessors.fromApplication(
                    appContext,
                    AppStateDataStoreEntryPoint::class.java,
                ).appStateDataStore().edit { it[key] = height }
            }.onFailure {
                L.w { "[ChatPopupKeyboard] save kb height failed: ${it.stackTraceToString()}" }
            }
        }
    }

    // endregion
}
