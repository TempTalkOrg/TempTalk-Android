package com.difft.android.login

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.widget.DifftClearableInputView
import com.difft.android.login.viewmodel.TestScopeApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Detached-inflate pin for the profile name field: the shared single-line input at the old id,
 * capped at 30 characters, with its own clear icon in place of the hand-made clear button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w360dp-h640dp")
class ContactProfileLayoutMigrationTest {

    private fun View.editTexts(): List<EditText> = when (this) {
        is EditText -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).editTexts() }
        else -> emptyList()
    }

    @Test
    fun nameField_isTheSharedSingleLineInput() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themed = android.view.ContextThemeWrapper(context, com.difft.android.base.R.style.Theme_Chative)
        val root = LayoutInflater.from(themed).inflate(R.layout.activity_contact_profile_setting, null, false)
        val name = root.findViewById<View>(R.id.et_name)
        assertTrue(name is DifftClearableInputView, "et_name is ${name?.javaClass?.name}")
        assertEquals(1, name.maxLines)
        assertEquals(30, name.maxLength)
        assertTrue(name.autoFocus, "the name field no longer opens with focus")
        val legacyClear = root.resources.getIdentifier("btn_clear", "id", root.context.packageName)
        assertTrue(legacyClear == 0 || root.findViewById<View>(legacyClear) == null, "hand-made clear button survived")
        assertTrue(root.editTexts().isEmpty(), "raw EditText left in activity_contact_profile_setting")
    }
}
