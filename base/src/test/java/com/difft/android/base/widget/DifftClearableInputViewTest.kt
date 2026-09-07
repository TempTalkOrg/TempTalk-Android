package com.difft.android.base.widget

import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import com.difft.android.base.R
import com.difft.android.base.ui.compose.input.ClearMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Setter-semantics pins for [DifftClearableInputView] — the AbstractComposeView shell.
 *
 * The contract under guard (each row maps to a real call-site dependency):
 * - external `query =` setter fires NO callback (SearchMessageActivity's initial-key backfill
 *   relies on silence; GroupInCommonActivity's group-update chain adds its own explicit refresh)
 * - `clear()` fires onClear (not onQueryChanged) and fires it UNCONDITIONALLY even from an
 *   already-empty state — TextView.setText notifies its watchers unconditionally, and
 *   GroupInCommonActivity's singleGroupsUpdate chain fires precisely when the text is already
 *   empty; an "already empty, skip" optimization would silently sever that refresh
 * - `clear()` does not move focus (legacy `performClick()`-as-API semantics in
 *   CreateGroupActivity)
 * - instance-state restore refires onQueryChanged (EditText's restore fires the TextWatcher)
 *   and never routes through onClear
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftClearableInputViewTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun mountView(configure: DifftClearableInputView.() -> Unit = {}): DifftClearableInputView {
        lateinit var view: DifftClearableInputView
        rule.activityRule.scenario.onActivity { activity ->
            view = DifftClearableInputView(activity).apply {
                id = VIEW_ID
                configure()
            }
            activity.setContentView(view)
        }
        rule.waitForIdle()
        return view
    }

    // B2/B3: subclass constructor defaults vs base defaults (Kotlin init-order trap pin)
    @Test
    fun searchSubclass_defaultsToWhenNotEmptyAndSearchAction() {
        lateinit var view: DifftSearchInputView
        rule.activityRule.scenario.onActivity { activity ->
            view = DifftSearchInputView(activity)
            activity.setContentView(view)
        }
        rule.waitForIdle()
        assertEquals(ClearMode.WhenNotEmpty, view.clearMode)
        assertEquals(ImeAction.Search, view.keyboardOptions.imeAction)
    }

    @Test
    fun baseClass_defaultsToWhileEditingAndDefaultAction() {
        val view = mountView()
        assertEquals(ClearMode.WhileEditing, view.clearMode)
        assertEquals(ImeAction.Default, view.keyboardOptions.imeAction)
    }

    // B4: external setter fires no callback
    @Test
    fun externalSetter_firesNoCallback() {
        var queryChanges = 0
        var clears = 0
        val view = mountView {
            onQueryChanged = { queryChanges++ }
            onClear = { clears++ }
        }
        rule.runOnUiThread { view.query = "abc" }
        rule.waitForIdle()
        assertEquals("abc", view.query)
        assertEquals(0, queryChanges)
        assertEquals(0, clears)
    }

    // B6: clear() fires onClear once, onQueryChanged never, and empties the text
    @Test
    fun clear_firesOnClearOnly() {
        var queryChanges = 0
        var clears = 0
        val view = mountView {
            onQueryChanged = { queryChanges++ }
            onClear = { clears++ }
        }
        rule.runOnUiThread {
            view.query = "abc"
            view.clear()
        }
        rule.waitForIdle()
        assertEquals("", view.query)
        assertEquals(1, clears)
        assertEquals(0, queryChanges)
    }

    // B11 (the singleGroupsUpdate pin): clear() fires onClear even when already empty
    @Test
    fun clear_firesOnClearEvenWhenAlreadyEmpty() {
        var clears = 0
        val view = mountView { onClear = { clears++ } }
        rule.runOnUiThread { view.clear() }
        rule.waitForIdle()
        assertEquals(1, clears)
    }

    // B7: clear() does not move focus
    @Test
    fun clear_doesNotStealOrGrantFocus() {
        val view = mountView { clearMode = ClearMode.WhenNotEmpty }
        rule.runOnUiThread { view.query = "abc" }
        rule.waitForIdle()
        val field = rule.onNode(hasSetTextAction())
        field.assertIsNotFocused()
        rule.runOnUiThread { view.clear() }
        rule.waitForIdle()
        field.assertIsNotFocused()
    }

    // (a): user typing fires onQueryChanged with the new text
    @Test
    fun typing_firesOnQueryChangedAndUpdatesQuery() {
        val received = mutableListOf<String>()
        val view = mountView { onQueryChanged = { received.add(it) } }
        rule.onNode(hasSetTextAction()).performTextInput("hi")
        rule.waitForIdle()
        assertEquals("hi", view.query)
        assertEquals("hi", received.last())
    }

    // (d): user taps ✕ -> onClear + refocus
    @Test
    fun userTapClear_firesOnClearAndEmptiesQuery() {
        var clears = 0
        val view = mountView {
            clearMode = ClearMode.WhenNotEmpty
            onClear = { clears++ }
        }
        rule.runOnUiThread { view.query = "abc" }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.base_clear_text))
            .performClick()
        rule.waitForIdle()
        assertEquals(1, clears)
        assertEquals("", view.query)
        rule.onNode(hasSetTextAction()).assertIsFocused()
    }

    // B9 + X3: instance state round-trip restores text, refires onQueryChanged exactly once,
    // and never fires onClear
    @Test
    fun instanceState_roundTripRestoresQueryAndRefiresOnQueryChanged() {
        val view = mountView()
        rule.runOnUiThread { view.query = "abc" }
        rule.waitForIdle()

        val container = SparseArray<Parcelable>()
        rule.runOnUiThread { view.saveHierarchyState(container) }

        var queryChanges = 0
        var clears = 0
        var restoredValue: String? = null
        rule.activityRule.scenario.onActivity { activity ->
            val restored = DifftClearableInputView(activity).apply {
                id = VIEW_ID
                onQueryChanged = { queryChanges++; restoredValue = it }
                onClear = { clears++ }
            }
            activity.setContentView(FrameLayout(activity).apply { addView(restored) })
            restored.restoreHierarchyState(container)
            shadowOf(activity.mainLooper).idle()
            assertEquals("abc", restored.query)
        }
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { activity ->
            shadowOf(activity.mainLooper).idle()
        }
        assertEquals(1, queryChanges)
        assertEquals("abc", restoredValue)
        assertEquals(0, clears)
    }

    // Instance-state save uses a Bundle wrapper (not TextView freezing) — sanity pin
    @Test
    fun savedHierarchyState_containsQuery() {
        val view = mountView()
        rule.runOnUiThread { view.query = "xyz" }
        rule.waitForIdle()
        val container = SparseArray<Parcelable>()
        rule.runOnUiThread { view.saveHierarchyState(container) }
        val bundle = container.get(VIEW_ID) as Bundle
        assertEquals("xyz", bundle.getString("difft_input_query"))
    }

    private companion object {
        const val VIEW_ID = 0x7f0f0001
    }
}
