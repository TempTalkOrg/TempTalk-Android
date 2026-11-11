package com.difft.android.chat.group

import com.difft.android.network.group.GetGroupInfoResp
import org.difft.app.database.models.GroupModel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupDetailExpiryTest {
    @Test
    fun `detail with omitted deadline keeps the stored deadline`() {
        val stored = group(STORED_DEADLINE)

        stored.applyDetailExpiry(detail())

        assertEquals(STORED_DEADLINE, stored.expireTime)
    }

    @Test
    fun `nonpositive detail deadlines and absent body keep the stored deadline`() {
        for (response in listOf(null, detail().copy(expireTime = 0L), detail().copy(expireTime = -1L))) {
            val stored = group(STORED_DEADLINE)

            stored.applyDetailExpiry(response)

            assertEquals(STORED_DEADLINE, stored.expireTime)
        }
    }

    @Test
    fun `newer positive detail deadline replaces the stored deadline`() {
        val stored = group(STORED_DEADLINE)

        stored.applyDetailExpiry(detail().copy(expireTime = NEWER_DEADLINE))

        assertEquals(NEWER_DEADLINE, stored.expireTime)
    }

    @Test
    fun `omitted deadline leaves an unset deadline empty while positive detail populates it`() {
        val stored = group(null)

        stored.applyDetailExpiry(detail())
        assertNull(stored.expireTime)

        stored.applyDetailExpiry(detail().copy(expireTime = NEWER_DEADLINE))
        assertEquals(NEWER_DEADLINE, stored.expireTime)
    }

    private fun group(deadline: Long?) = GroupModel().apply {
        gid = "group"
        expireTime = deadline
    }

    private fun detail() = GetGroupInfoResp(
        anyoneRemove = false,
        avatar = "",
        ext = false,
        invitationRule = 0,
        members = emptyList(),
        messageExpiry = 0,
        name = "Group",
        publishRule = 0,
        rejoin = false,
        remindCycle = "",
        version = 1,
        linkInviteSwitch = false,
        privateChat = false,
        messageClearAnchor = 0L,
        criticalAlert = false,
    )

    private companion object {
        const val STORED_DEADLINE = 1_800_000_000_000L
        const val NEWER_DEADLINE = 1_800_086_400_000L
    }
}
