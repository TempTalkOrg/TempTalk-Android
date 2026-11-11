package com.difft.android.network.group

import com.difft.android.network.ChativeHttpClient
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupRepoExpiryTest {
    private val service = mockk<GroupService>()
    private val client = mockk<ChativeHttpClient> {
        every { getService(GroupService::class.java) } returns service
    }
    private val repo = GroupRepo(mockk(), client)

    @Test
    fun `from maps deadline without altering group metadata`() {
        val model = repo.from(response(1_800_000_000_000L))
        assertEquals(1_800_000_000_000L, model.expireTime)
        assertEquals("group", model.gid)
        assertEquals("name", model.name)
        assertEquals(3600, model.messageExpiry)
        assertEquals(3, model.version)
    }

    @Test
    fun `from normalizes absent and nonpositive deadlines`() {
        for (deadline in listOf(null, 0L, -1L)) {
            assertNull(repo.from(response(deadline)).expireTime)
        }
    }

    @Test
    fun `from maps the critical alert switch the list carries`() {
        assertEquals(true, repo.from(response(null, criticalAlert = true)).criticalAlert)
        assertEquals(false, repo.from(response(null, criticalAlert = false)).criticalAlert)
    }

    private fun response(expireTime: Long?, criticalAlert: Boolean = false) = GroupResp(
        anyoneRemove = false, avatar = "", ext = false, gid = "group", invitationRule = 0,
        linkInviteSwitch = false, messageExpiry = 3600, name = "name", publishRule = 0,
        rejoin = false, remindCycle = "", status = 0, version = 3, criticalAlert = criticalAlert,
        expireTime = expireTime,
    )
}
