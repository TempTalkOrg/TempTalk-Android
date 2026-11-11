package com.difft.android.chat.contacts.contactsdetail

import io.mockk.mockk
import org.difft.app.database.models.ContactorModel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContactDetailRepositoryRemarksTest {
    private val repository = ContactDetailRepository(mockk(), mockk(), mockk(), mockk(), mockk())

    @Test fun `a newer local row wins and the fetched profile only fills its gaps`() {
        val fetched = ContactorModel().apply {
            id = "contact"; name = "Old"; avatar = "old-avatar"; customUid = "old-custom"; joinedAt = "2020"
        }
        val row = ContactorModel().apply { id = "contact"; name = "New"; avatar = "new-avatar" }
        val merged = with(repository) { fetched.fillGapsOf(row) }
        assertEquals("New", merged.name)
        assertEquals("new-avatar", merged.avatar)
        assertEquals("old-custom", merged.customUid)
        assertEquals("2020", merged.joinedAt)
    }

    @Test fun `a fetched profile fills a sparse row and takes remarks from the current local row`() {
        val fetched = ContactorModel().apply {
            id = "contact"; name = "Server"; avatar = "server-avatar"; customUid = "custom"
            remark = "old remark"; remarkAvatar = "old-remark-avatar"
        }
        val cleared = with(repository) { fetched.fillGapsOf(ContactorModel().apply { id = "contact" }) }
        assertEquals("server-avatar", cleared.avatar)
        assertEquals("custom", cleared.customUid)
        assertEquals("Server", cleared.name)
        assertNull(cleared.remark)
        assertNull(cleared.remarkAvatar)

        val edited = with(repository) { fetched.fillGapsOf(ContactorModel().apply { remark = "new remark" }) }
        assertEquals("new remark", edited.remark)
        
        assertEquals("old remark", fetched.remark)
    }
}
