package com.difft.android.base.widget

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.util.AttributeSet
import android.util.SparseArray
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.difft.android.base.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.concurrent.thread

/**
 * The [DifftToggleView] contract, run once per concrete shell (issues #1203 / #1206). Every
 * programmatic path is silent, only a click fires a listener, the two listeners are mutually
 * exclusive modes, an in-flight request swallows taps whole, and checked state survives
 * save/restore.
 *
 * Subclasses supply the shell under test and carry the Robolectric runner annotations; only
 * shell-specific behaviour (measurement, labels) belongs in them.
 */
abstract class DifftToggleViewContractTest {

    protected abstract fun create(context: Context, attrs: AttributeSet? = null): DifftToggleView

    /** The class the shell reports to TalkBack, e.g. `android.widget.CheckBox`. */
    protected abstract val expectedAccessibilityClassName: String

    protected fun host(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    protected fun mount(view: DifftToggleView, activity: ComponentActivity) {
        activity.setContentView(FrameLayout(activity).apply { addView(view) })
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---------- Programmatic paths are silent ----------

    @Test
    fun `programmatic set changes state without firing the listener`() {
        val activity = host()
        val view = create(activity)
        var fired = 0
        view.setOnCheckedChangeListener { _, _ -> fired++ }
        mount(view, activity)

        view.isChecked = true
        view.isChecked = true // equal value: no-op

        assertTrue(view.isChecked)
        assertEquals(0, fired)
    }

    @Test
    fun `toggle flips silently`() {
        val activity = host()
        val view = create(activity)
        var fired = 0
        view.setOnCheckedChangeListener { _, _ -> fired++ }
        mount(view, activity)

        view.toggle()

        assertTrue(view.isChecked)
        assertEquals(0, fired)
    }

    @Test
    fun `disabled view ignores clicks but still accepts a programmatic set`() {
        val activity = host()
        val view = create(activity)
        var fired = 0
        view.setOnCheckedChangeListener { _, _ -> fired++ }
        mount(view, activity)

        view.isEnabled = false
        view.performClick()
        assertFalse(view.isChecked)
        assertEquals(0, fired)

        view.isChecked = true
        assertTrue(view.isChecked)
    }

    // ---------- The two modes ----------

    @Test
    fun `auto mode click toggles and fires the listener once with the new value`() {
        val activity = host()
        val view = create(activity)
        val received = mutableListOf<Boolean>()
        view.setOnCheckedChangeListener { _, checked -> received += checked }
        mount(view, activity)

        view.performClick()
        assertTrue(view.isChecked)
        view.performClick()
        assertFalse(view.isChecked)

        assertEquals(listOf(true, false), received)
    }

    @Test
    fun `controlled mode click reports intent without changing the state`() {
        val activity = host()
        val view = create(activity)
        val received = mutableListOf<Boolean>()
        view.setOnToggleRequestListener { _, requested -> received += requested }
        mount(view, activity)

        view.performClick()
        view.performClick()

        // Idempotent: the state never moved, so both taps request the same value.
        assertEquals(listOf(true, true), received)
        assertFalse(view.isChecked)
    }

    @Test
    fun `the two listeners are mutually exclusive`() {
        val activity = host()
        val auto = create(activity)
        var autoFired = 0
        var controlledFired = 0
        auto.setOnCheckedChangeListener { _, _ -> autoFired++ }
        auto.setOnToggleRequestListener { _, _ -> controlledFired++ }
        mount(auto, activity)

        auto.performClick()
        assertEquals(0, autoFired)
        assertEquals(1, controlledFired)
        assertFalse(auto.isChecked)

        val reversed = create(activity)
        var autoFired2 = 0
        var controlledFired2 = 0
        reversed.setOnToggleRequestListener { _, _ -> controlledFired2++ }
        reversed.setOnCheckedChangeListener { _, _ -> autoFired2++ }
        mount(reversed, activity)

        reversed.performClick()
        assertEquals(1, autoFired2)
        assertEquals(0, controlledFired2)
        assertTrue(reversed.isChecked)
    }

    @Test
    fun `resetting the state inside the callback does not recurse`() {
        val activity = host()
        val view = create(activity)
        var fired = 0
        view.setOnCheckedChangeListener { v, checked ->
            fired++
            v.isChecked = !checked
        }
        mount(view, activity)

        view.performClick()

        assertEquals(1, fired)
        assertFalse(view.isChecked)
    }

    // ---------- In-flight guard ----------

    @Test
    fun `an in-flight request swallows taps while programmatic sets still land`() {
        val activity = host()
        val view = create(activity)
        val received = mutableListOf<Boolean>()
        var clicks = 0
        view.setOnToggleRequestListener { _, requested -> received += requested }
        view.setOnClickListener { clicks++ }
        mount(view, activity)

        view.toggleRequestInFlight = true
        // The tap is swallowed whole: unhandled, and nothing downstream sees it.
        assertFalse(view.performClick())
        assertFalse(view.performClick())
        assertTrue(received.isEmpty())
        assertEquals(0, clicks)
        assertFalse(view.isChecked)

        view.isChecked = true
        assertTrue(view.isChecked)

        view.toggleRequestInFlight = false
        view.performClick()
        assertEquals(listOf(false), received)
        assertEquals(1, clicks)
    }

    @Test
    fun `an in-flight request also swallows auto mode taps`() {
        val activity = host()
        val view = create(activity)
        var fired = 0
        var clicks = 0
        view.setOnCheckedChangeListener { _, _ -> fired++ }
        view.setOnClickListener { clicks++ }
        mount(view, activity)

        view.toggleRequestInFlight = true
        assertFalse(view.performClick())

        assertEquals(0, fired)
        assertEquals(0, clicks)
        assertFalse(view.isChecked)
    }

    @Test
    fun `guardWhile holds the guard while the job runs and releases it on completion`() {
        val activity = host()
        val view = create(activity)
        mount(view, activity)
        val gate = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { gate.await() }

        view.guardWhile(job)
        assertTrue(view.toggleRequestInFlight)

        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(view.toggleRequestInFlight)
    }

    @Test
    fun `guardWhile releases on the main thread when the job settles off it`() {
        val activity = host()
        val view = create(activity)
        mount(view, activity)
        val gate = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { gate.await() }
        var released = 0

        view.guardWhile(job) { released++ }
        assertTrue(view.toggleRequestInFlight)

        // Unconfined: the job completes on the thread that completed the gate, so the release has
        // to be marshalled back to the main thread before either side of it runs.
        thread { gate.complete(Unit) }.join()
        assertTrue(view.toggleRequestInFlight)
        assertEquals(0, released)

        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(view.toggleRequestInFlight)
        assertEquals(1, released)
    }

    @Test
    fun `guardWhile releases the guard when the job is cancelled`() {
        val activity = host()
        val view = create(activity)
        mount(view, activity)
        val gate = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { gate.await() }

        view.guardWhile(job)
        assertTrue(view.toggleRequestInFlight)

        job.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(view.toggleRequestInFlight)
    }

    // ---------- Instance state ----------

    @Test
    fun `checked state survives save and restore`() {
        val activity = host()
        val view = create(activity).apply { id = 1 }
        mount(view, activity)
        view.isChecked = true
        val container = SparseArray<Parcelable>()
        view.saveHierarchyState(container)

        val restored = create(activity).apply { id = 1 }
        restored.restoreHierarchyState(container)

        assertTrue(container.get(1) is Bundle)
        assertTrue(restored.isChecked)
    }

    @Test
    fun `restore does not fire the listener`() {
        val activity = host()
        val view = create(activity).apply { id = 1 }
        mount(view, activity)
        view.isChecked = true
        val container = SparseArray<Parcelable>()
        view.saveHierarchyState(container)

        val restored = create(activity).apply { id = 1 }
        var fired = 0
        restored.setOnCheckedChangeListener { _, _ -> fired++ }
        restored.restoreHierarchyState(container)

        assertTrue(restored.isChecked)
        assertEquals(0, fired)
    }

    @Test
    fun `saveEnabled false skips the checked state`() {
        val activity = host()
        val view = create(activity).apply {
            id = 1
            isSaveEnabled = false
        }
        mount(view, activity)
        view.isChecked = true
        val container = SparseArray<Parcelable>()
        view.saveHierarchyState(container)
        assertNull(container.get(1))
    }

    // ---------- Attributes and accessibility ----------

    @Test
    fun `xml attributes land on the shell`() {
        val activity = host()
        val attrs = Robolectric.buildAttributeSet()
            .addAttribute(android.R.attr.checked, "true")
            .addAttribute(android.R.attr.enabled, "false")
            .addAttribute(R.attr.difft_forceDark, "true")
            .build()
        val view = create(activity, attrs)

        assertTrue(view.isChecked)
        assertFalse(view.isEnabled)
        assertTrue(view.forceDark)
    }

    @Test
    fun `xml clickable and focusable false are honoured so a row can own selection`() {
        val activity = host()
        val attrs = Robolectric.buildAttributeSet()
            .addAttribute(android.R.attr.clickable, "false")
            .addAttribute(android.R.attr.focusable, "false")
            .build()
        val view = create(activity, attrs)

        assertFalse(view.isClickable)
        assertFalse(view.isFocusable)
        assertTrue(create(activity).isClickable)
    }

    @Test
    fun `reports its accessibility class with the current checked state`() {
        val activity = host()
        val view = create(activity)
        mount(view, activity)
        view.isChecked = true

        val info = view.createAccessibilityNodeInfo()

        assertEquals(expectedAccessibilityClassName, view.accessibilityClassName)
        assertTrue(info.isCheckable)
        assertTrue(info.isChecked)
    }
}
