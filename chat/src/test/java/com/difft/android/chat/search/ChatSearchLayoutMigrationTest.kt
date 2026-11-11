package com.difft.android.chat.search

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.widget.DifftSearchInputView
import com.difft.android.chat.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class ChatSearchLayoutMigrationTest {

    private fun inflate(layoutId: Int): View {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themed = android.view.ContextThemeWrapper(context, com.difft.android.base.R.style.Theme_Chative)
        return LayoutInflater.from(themed).inflate(layoutId, null, false)
    }

    private fun assertMigrated(layoutId: Int, layoutName: String, expectAutoFocus: Boolean) {
        val root = inflate(layoutId)
        val input = root.findViewById<View>(R.id.search_input)
        assertTrue(input is DifftSearchInputView, "$layoutName: search_input is ${input?.javaClass?.name}")
        
        val legacyId = root.resources.getIdentifier("button_clear", "id", root.context.packageName)
        assertTrue(legacyId == 0 || root.findViewById<View>(legacyId) == null,
            "$layoutName: legacy button_clear survived")
    }

    @Test
    fun searchMessageLayout_migrated() =
        assertMigrated(R.layout.activity_search_message, "activity_search_message", expectAutoFocus = true)

    @Test
    fun groupInCommonLayout_migrated() =
        assertMigrated(R.layout.activity_group_in_common, "activity_group_in_common", expectAutoFocus = false)

    @Test
    fun createGroupLayout_migrated() =
        assertMigrated(R.layout.chat_activity_create_group, "chat_activity_create_group", expectAutoFocus = false)

    @Test
    fun groupSelectMemberLayout_migrated() =
        assertMigrated(R.layout.chat_activity_group_select_member, "chat_activity_group_select_member", expectAutoFocus = false)

    @Test
    fun forwardSelectChatLayout_migrated() =
        assertMigrated(R.layout.chat_layout_forward_select_chat, "chat_layout_forward_select_chat", expectAutoFocus = true)

    
    
    
    @Test
    fun pseudoSearchEntryPoints_surviveUntouched() {
        listOf(
            R.layout.chat_fragment_search_input to "chat_fragment_search_input",
            R.layout.chat_activity_group_info to "chat_activity_group_info",
        ).forEach { (layoutId, name) ->
            val root = inflate(layoutId)
            val entry = root.findViewById<View>(R.id.edittext_search_input)
            assertTrue(entry is AppCompatTextView, "$name: pseudo-search entry is ${entry?.javaClass?.name}")
            val legacyId = root.resources.getIdentifier("button_clear", "id", root.context.packageName)
            assertTrue(legacyId == 0 || root.findViewById<View>(legacyId) == null, "$name: unexpected button_clear")
        }
    }
}
