package com.difft.android.base.widget

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.util.AttributeSet
import android.view.SoundEffectConstants
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.withStyledAttributes
import com.difft.android.base.R
import com.difft.android.base.concurrent.AppExecutors
import kotlinx.coroutines.Job

/**
 * Shared shell for the two-state controls ([DifftCheckBoxView], [DifftSwitchView]). Implements
 * [Checkable] so it is a drop-in for `CompoundButton` call sites — `isChecked`, `toggle()`,
 * `setOnCheckedChangeListener`, `setOnClickListener` — under three rules:
 *
 * - **Every programmatic path is silent**: `isChecked = x`, [toggle], `onRestoreInstanceState`, a
 *   Flow write-back. An equal value is a complete no-op. Works while disabled too.
 * - **Only real user input calls a listener** — [performClick] (touch, keyboard, TalkBack
 *   ACTION_CLICK). The value handed to the listener is the one the user asked for, so callers never
 *   read `isChecked` back to infer it.
 * - **Two listeners, two modes, mutually exclusive**: [setOnCheckedChangeListener] (auto — the
 *   state has already flipped) and [setOnToggleRequestListener] (controlled — the click only
 *   reports intent and the caller applies it).
 *
 * `CompoundButton` fires the listener on programmatic sets too, which is why old binders detach the
 * listener, set the value, then re-attach. With this shell that dance is unnecessary: an external
 * state change is just an assignment.
 *
 * Clicks toggle when enabled and no controlled listener is attached (mirrors
 * `CompoundButton.performClick`), then any `OnClickListener` runs, so a row that owns selection can
 * keep `isClickable = false` and set the state from data. Checked state survives configuration
 * changes like a `CompoundButton`.
 *
 * XML: `android:checked`, `android:enabled`, `app:difft_forceDark`, plus `android:clickable` /
 * `android:focusable`, which default to true here (unlike a plain View) and are set to false by a
 * row that owns selection and toggles the box from data.
 */
abstract class DifftToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftComposeView(context, attrs, defStyleAttr), Checkable {

    private var checkedState by mutableStateOf(false)
    private var enabledState by mutableStateOf(true)
    private var onCheckedChangeListener: ((DifftToggleView, Boolean) -> Unit)? = null
    private var onToggleRequestListener: ((DifftToggleView, Boolean) -> Unit)? = null

    /**
     * While true, a user tap is swallowed whole: [performClick] returns false without flipping the
     * state, calling a listener, playing the click sound or dispatching a click event, so nothing
     * downstream (an `OnClickListener`, TalkBack) sees the tap at all. Programmatic `isChecked`
     * still works.
     *
     * Set it around an asynchronous request — [guardWhile] does that from a [Job] — so alternating
     * rapid taps cannot produce out-of-order results: without it, a tap that lands after an earlier
     * request has already updated the state requests the opposite value, and the two responses can
     * arrive reversed.
     */
    var toggleRequestInFlight: Boolean = false

    /**
     * Holds [toggleRequestInFlight] for the lifetime of [job], then runs [onRelease]. Release
     * happens however the job settles — success, failure or cancellation — always on the main
     * thread, and even if this View has been detached, so a cancelled scope can never leave the
     * control permanently deaf to taps.
     *
     * [onRelease] is the place for the UI the request owns (dismissing a wait dialog): one
     * completion handler, one main-thread hop, instead of a second hook on the same job.
     */
    fun guardWhile(job: Job, onRelease: (() -> Unit)? = null) {
        toggleRequestInFlight = true
        val release = {
            toggleRequestInFlight = false
            onRelease?.invoke()
        }
        job.invokeOnCompletion {
            // A main-thread Handler, not View.post: a detached View silently drops its messages.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                release()
            } else {
                AppExecutors.mainHandler().post { release() }
            }
        }
    }

    /** Accessibility class reported to TalkBack (e.g. `CheckBox`, `Switch`). */
    protected abstract val accessibilityClassName: String

    init {
        // CompoundButton defaults: clickable + focusable — but only when the layout did not say
        // otherwise (a row that owns selection sets android:clickable="false" on the box).
        var clickable = true
        var focusable = true
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.DifftToggleView) {
                clickable = getBoolean(R.styleable.DifftToggleView_android_clickable, true)
                focusable = getBoolean(R.styleable.DifftToggleView_android_focusable, true)
                checkedState = getBoolean(R.styleable.DifftToggleView_android_checked, false)
                if (hasValue(R.styleable.DifftToggleView_android_enabled)) {
                    // The framework does not apply android:enabled on a plain View; route it through
                    // the setter so both the View flag and the Compose state see it.
                    isEnabled = getBoolean(R.styleable.DifftToggleView_android_enabled, true)
                }
            }
        }
        isClickable = clickable
        isFocusable = focusable
    }

    /**
     * Compose lazily on first measure instead of on attach: a GONE box (the common state of the
     * message-list and contact-row checkboxes) then costs no composition at all, and the shell keeps
     * every View-facing state outside the composition so nothing is lost.
     */
    override val shouldCreateCompositionOnAttachedToWindow: Boolean
        get() = false

    // ---------- Checkable ----------

    override fun isChecked(): Boolean = checkedState

    /** Programmatic set: no listener callback; an equal value is a complete no-op. */
    override fun setChecked(checked: Boolean) {
        if (checkedState == checked) return
        checkedState = checked
        notifyCheckedChangedForAccessibility()
    }

    /**
     * Programmatic flip. Silent, exactly like [setChecked] — a `Checkable` convenience, not a way
     * to simulate user input. Use [performClick] for that.
     */
    override fun toggle() = setChecked(!checkedState)

    /**
     * Auto mode: the click has already flipped the state when this runs (`CompoundButton`
     * semantics). For toggles that take effect locally and cannot fail. Rolling back is
     * `view.isChecked = !newValue` — silent, no recursion.
     *
     * Mutually exclusive with [setOnToggleRequestListener]: setting a non-null listener here clears
     * that one. Passing null only detaches this one.
     */
    fun setOnCheckedChangeListener(listener: ((view: DifftToggleView, isChecked: Boolean) -> Unit)?) {
        onCheckedChangeListener = listener
        if (listener != null) onToggleRequestListener = null
    }

    /**
     * Controlled mode: the click does NOT change the state, it only reports the value the user
     * asked for. The caller applies it with `isChecked = requested` once the operation succeeds; on
     * failure there is nothing to roll back because nothing moved.
     *
     * Repeat taps are idempotent while the state has not changed, so a rollback dance is never
     * needed. For an asynchronous operation pass its job to [guardWhile] anyway: once a response
     * lands, further taps request the opposite value and could complete out of order.
     *
     * Mutually exclusive with [setOnCheckedChangeListener].
     */
    fun setOnToggleRequestListener(listener: ((view: DifftToggleView, requested: Boolean) -> Unit)?) {
        onToggleRequestListener = listener
        if (listener != null) onCheckedChangeListener = null
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        enabledState = enabled
    }

    override fun performClick(): Boolean {
        if (isEnabled) {
            // Swallow the tap whole while a request is in flight: no sound, no OnClickListener, no
            // TYPE_VIEW_CLICKED. Anything less would report a click that changed nothing.
            if (toggleRequestInFlight) return false
            dispatchUserToggle()
        }
        val handled = super.performClick()
        if (!handled) playSoundEffect(SoundEffectConstants.CLICK)
        return handled
    }

    /**
     * The one path a listener ever fires from: real user input (touch, keyboard, TalkBack
     * ACTION_CLICK). With no listener attached the state flips, so a row that drives selection from
     * data keeps `CompoundButton` behaviour via `setOnClickListener`.
     */
    private fun dispatchUserToggle() {
        val requested = !checkedState
        val controlled = onToggleRequestListener
        if (controlled != null) {
            controlled(this, requested)
            return
        }
        checkedState = requested
        // TYPE_VIEW_CLICKED comes from super.performClick(); only the state change is announced here.
        notifyCheckedChangedForAccessibility()
        onCheckedChangeListener?.invoke(this, requested)
    }

    /**
     * Content-changed announcement so TalkBack re-reads the new state. Dispatched per View; the
     * framework's per-frame subtree merging is hidden API, so a bulk select-all emits one event per
     * visible row (accessibility enabled only).
     */
    private fun notifyCheckedChangedForAccessibility() =
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

    // ---------- Accessibility ----------

    override fun getAccessibilityClassName(): CharSequence = accessibilityClassName

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.isChecked = checkedState
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = checkedState
    }

    // ---------- Instance state ----------

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable(KEY_SUPER, super.onSaveInstanceState())
        putBoolean(KEY_CHECKED, checkedState)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            super.onRestoreInstanceState(state.getParcelable(KEY_SUPER))
            checkedState = state.getBoolean(KEY_CHECKED, false)
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    // ---------- Content ----------

    @Composable
    final override fun ThemedContent() = ToggleContent(checked = checkedState, enabled = enabledState)

    /**
     * The control's visual for the current state. It must be non-interactive (the shell owns
     * clicks and semantics). Sizing rule: express the wrap_content footprint with `defaultMinSize`
     * and centre the glyph in a `Box`; never `fillMaxSize` — a wrap_content View receives AT_MOST
     * constraints from its parent, which `fillMaxSize` would expand to the whole row.
     * `AbstractComposeView.onMeasure` ignores View-level minimumWidth/Height, hence Compose-side.
     */
    @Composable
    protected abstract fun ToggleContent(checked: Boolean, enabled: Boolean)

    private companion object {
        const val KEY_SUPER = "difft_toggle_super"
        const val KEY_CHECKED = "difft_toggle_checked"
    }
}
