package com.difft.android.chat.contacts.groupcard

import com.difft.android.chat.contacts.groupcard.mvi.GroupCardDataSource.Snapshot
import com.difft.android.chat.contacts.groupcard.mvi.mapGroupCardState
import com.difft.android.chat.group.GROUP_ROLE_ADMIN
import com.difft.android.chat.group.GROUP_ROLE_MEMBER
import com.difft.android.chat.group.GROUP_ROLE_OWNER
import org.difft.app.database.models.GroupModel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupCardStateTest {
    @Test
    fun `normal group shows membership and message expiry without a dissolve warning`() {
        val group = group().apply { messageExpiry = 86_400 }
        val state = mapGroupCardState(GID, Snapshot(group, 29, GROUP_ROLE_MEMBER, canSpeak = true), NOW)
        assertEquals("Project", state.name)
        assertEquals(29, state.memberCount)
        assertEquals(86_400L, state.messageExpirySeconds)
        assertNull(state.dissolveDays)
        assertTrue(state.actionsEnabled)
        assertTrue(state.inviteEnabled)
        assertTrue(state.callAllowed)
    }

    @Test
    fun `expiring group uses rounded up days and today at the deadline`() {
        val group = group().apply { expireTime = NOW + DAY + 1 }
        assertEquals(2, mapGroupCardState(GID, Snapshot(group), NOW).dissolveDays)
        group.expireTime = NOW
        assertEquals(0, mapGroupCardState(GID, Snapshot(group), NOW).dissolveDays)
        group.expireTime = NOW - 1
        assertEquals(0, mapGroupCardState(GID, Snapshot(group), NOW).dissolveDays)
    }

    @Test
    fun `absent or nonpositive deadlines have no dissolve warning`() {
        for (deadline in listOf(null, 0L, -1L)) {
            val group = group().apply { expireTime = deadline }
            assertNull(mapGroupCardState(GID, Snapshot(group), NOW).dissolveDays)
        }
    }

    @Test
    fun `missing unknown or invalid groups disable every action and warning`() {
        val unavailable = listOf(null, group().apply { status = null }, group().apply { status = 1 },
            group().apply { status = 2 }, group().apply { gid = "other" })
        for (group in unavailable) {
            group?.expireTime = NOW + DAY
            val state = mapGroupCardState(GID, Snapshot(group, 20, GROUP_ROLE_OWNER, canSpeak = true), NOW)
            assertFalse(state.actionsEnabled)
            assertFalse(state.inviteEnabled)
            assertFalse(state.callAllowed)
            assertNull(state.dissolveDays)
        }
    }

    @Test
    fun `invitation follows the server rule 2 anyone 1 moderators 0 owner and unset means anyone`() {
        for (role in listOf(GROUP_ROLE_OWNER, GROUP_ROLE_ADMIN, GROUP_ROLE_MEMBER)) {
            for (rule in listOf(null, 0, 1, 2)) {
                val group = group().apply { invitationRule = rule }
                val state = mapGroupCardState(GID, Snapshot(group, selfRole = role), NOW)
                val expected = when (rule) {
                    null, 2 -> true
                    1 -> role <= GROUP_ROLE_ADMIN
                    else -> role == GROUP_ROLE_OWNER
                }
                assertEquals(expected, state.inviteEnabled, "role=$role rule=$rule")
            }
        }
    }

    @Test
    fun `speech restriction preserves message and settings actions but gates the call`() {
        val state = mapGroupCardState(GID, Snapshot(group(), canSpeak = false), NOW)
        assertTrue(state.actionsEnabled)
        assertFalse(state.callAllowed)
    }

    private fun group() = GroupModel().apply {
        gid = GID
        name = "Project"
        status = 0
        invitationRule = 2
    }

    private companion object {
        const val GID = "group"
        const val NOW = 1_800_000_000_000L
        const val DAY = 86_400_000L
    }
}
