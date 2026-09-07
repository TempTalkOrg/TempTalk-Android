package com.difft.android.base.widget

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Framework-assumption pins (real EditText, zero mocks) that the shell's contract is built on.
 *
 * E5: TextView.setText notifies TextWatchers UNCONDITIONALLY — it does not short-circuit when
 * the new text equals the old text. This is why [DifftClearableInputView.clear] must fire
 * [DifftClearableInputView.onClear] even from an already-empty state: GroupInCommonActivity's
 * group-update refresh chain historically relied on `text = null` firing the watcher while the
 * field was already empty. If this pin ever fails (framework change), the clear() contract
 * needs re-evaluation.
 *
 * E4: EditText freezes its text into instance state by default (getFreezesText() == true), so
 * text survives recreation with no host code. The shell replicates this with an explicit
 * onSaveInstanceState — DifftClearableInputViewTest pins the replica; this pins the original.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextWatcherNotificationFrameworkTest {

    @Test
    fun setTextNull_onAlreadyEmptyEditText_stillNotifiesWatcherExactlyOnce() {
        val editText = EditText(ApplicationProvider.getApplicationContext())
        var afterChanges = 0
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                afterChanges++
            }
        })
        check(editText.text.toString().isEmpty())
        editText.text = null
        assertEquals(1, afterChanges)
    }

    @Test
    fun editText_freezesTextIntoInstanceStateByDefault() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = android.util.SparseArray<android.os.Parcelable>()
        EditText(context).apply {
            id = 42
            setText("abc")
            saveHierarchyState(container)
        }
        val restored = EditText(context).apply { id = 42 }
        restored.restoreHierarchyState(container)
        assertEquals("abc", restored.text.toString())
    }
}
