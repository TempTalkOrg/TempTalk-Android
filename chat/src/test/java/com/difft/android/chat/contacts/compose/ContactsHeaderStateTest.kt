package com.difft.android.chat.contacts.compose

import com.difft.android.chat.contacts.mvi.ContactsContract.State
import com.difft.android.chat.contacts.mvi.DirectorySection
import com.difft.android.chat.contacts.mvi.ContactDirectoryRow
import org.difft.app.database.models.ContactorModel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactsHeaderStateTest {
    @Test fun `header collapses and expands only through its range`() {
        val header = ContactsHeaderScrollState()
        header.updateRange(52f, listAtTop = true)
        assertEquals(-20f, header.scroll(-20f))
        assertEquals(20f, header.offsetPx)
        assertEquals(-32f, header.scroll(-100f))
        assertTrue(header.pinned)
        assertEquals(0f, header.scroll(-10f))
        assertEquals(52f, header.scroll(100f))
        assertEquals(0f, header.offsetPx)
    }

    @Test fun `upward drags collapse before the list and only the list's leftover expands`() {
        val header = ContactsHeaderScrollState()
        header.updateRange(100f, listAtTop = true)
        val connection = header.connection()
        assertEquals(Offset(0f, -40f), connection.onPreScroll(Offset(0f, -40f), NestedScrollSource.UserInput))
        
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, 30f), NestedScrollSource.UserInput))
        assertEquals(Offset(0f, 30f), connection.onPostScroll(Offset.Zero, Offset(0f, 30f), NestedScrollSource.UserInput))
        assertEquals(Offset(0f, 10f), connection.onPostScroll(Offset.Zero, Offset(0f, 50f), NestedScrollSource.UserInput))
        assertEquals(0f, header.offsetPx)
    }

    @Test fun `new strip heights keep pinned tabs over a scrolled list and otherwise clamp`() {
        val header = ContactsHeaderScrollState()
        header.updateRange(0f, listAtTop = true)
        header.updateRange(80f, listAtTop = true)
        assertEquals(0f, header.offsetPx)
        header.collapse()
        header.updateRange(120f, listAtTop = false)
        assertEquals(120f, header.offsetPx)
        header.updateRange(150f, listAtTop = true)
        assertEquals(120f, header.offsetPx)
        header.updateRange(60f, listAtTop = true)
        assertEquals(60f, header.offsetPx)
    }

    @Test fun `title collapse threshold stays exclusive and multiwindow never collapses`() {
        assertTrue(contactsTitleCanCollapse(699, false))
        assertFalse(contactsTitleCanCollapse(700, false))
        assertFalse(contactsTitleCanCollapse(620, true))
    }

    @Test fun `index positions follow the top anchor and count every section header`() {
        val alice = ContactDirectoryRow("a", "Alice", ContactorModel().apply { id = "a"; name = "Alice" })
        val state = State(contactSections = listOf(DirectorySection("A", listOf(alice)), DirectorySection("Z", emptyList())))
        assertEquals(mapOf("A" to 1, "Z" to 3), directorySectionIndices(state))
        assertEquals(emptyMap(), directorySectionIndices(State()))
    }
}
