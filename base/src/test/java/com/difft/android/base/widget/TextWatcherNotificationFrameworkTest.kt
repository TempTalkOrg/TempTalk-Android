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
