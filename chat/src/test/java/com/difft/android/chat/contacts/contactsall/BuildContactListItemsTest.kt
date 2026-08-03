package com.difft.android.chat.contacts.contactsall

import org.difft.app.database.models.ContactorModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [buildContactListItems] — the pure function that partitions merged contacts into a
 * top pending-removal group (expireAt != null) followed by the A-Z list, each pinyin-sorted.
 *
 * Runs under Robolectric because pinyin sorting resolves the display name through
 * getDisplayNameForUI, whose fallback chain touches Android-adjacent statics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BuildContactListItemsTest {

    private fun contact(id: String, name: String) = ContactorModel().apply {
        this.id = id
        this.name = name
    }

    @Test
    fun `pending group comes first, sorted by expireAt asc, normal group pinyin-sorted`() {
        val contacts = listOf(
            contact("u_b", "Bob"),      // normal
            contact("u_z", "Zoe"),      // pending, soonest expiry
            contact("u_a", "Alice"),    // normal
            contact("u_m", "Mia"),      // pending, later expiry
        )
        val weakExpire = mapOf("u_z" to 100L, "u_m" to 200L)

        val result = buildContactListItems(contacts, weakExpire)

        // Pending sorted by expireAt asc (Zoe=100 before Mia=200), then normal pinyin-sorted (Alice, Bob).
        assertEquals(listOf("u_z", "u_m", "u_a", "u_b"), result.map { it.contactor.id })
    }

    @Test
    fun `pending group with equal expireAt falls back to pinyin order`() {
        val contacts = listOf(
            contact("u_z", "Zoe"),      // pending, same expiry
            contact("u_m", "Mia"),      // pending, same expiry
        )
        val weakExpire = mapOf("u_z" to 500L, "u_m" to 500L)

        val result = buildContactListItems(contacts, weakExpire)

        // Equal expireAt → tie broken by pinyin (Mia before Zoe).
        assertEquals(listOf("u_m", "u_z"), result.map { it.contactor.id })
    }

    @Test
    fun `expireAt is carried through for pending and null for normal`() {
        val contacts = listOf(contact("u_a", "Alice"), contact("u_z", "Zoe"))
        val weakExpire = mapOf("u_z" to 999L)

        val result = buildContactListItems(contacts, weakExpire)

        val byId = result.associateBy { it.contactor.id }
        assertEquals(999L, byId.getValue("u_z").expireAt)
        assertNull(byId.getValue("u_a").expireAt)
    }

    @Test
    fun `empty pending keeps normal pinyin order unchanged`() {
        val contacts = listOf(contact("u_c", "Cara"), contact("u_a", "Alice"), contact("u_b", "Bob"))

        val result = buildContactListItems(contacts, emptyMap())

        assertEquals(listOf("u_a", "u_b", "u_c"), result.map { it.contactor.id })
        result.forEach { assertNull(it.expireAt) }
    }

    @Test
    fun `duplicate ids are de-duplicated`() {
        val contacts = listOf(contact("u_a", "Alice"), contact("u_a", "Alice"))

        val result = buildContactListItems(contacts, emptyMap())

        assertEquals(1, result.size)
    }
}
