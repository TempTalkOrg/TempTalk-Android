package com.difft.android.chat.group

import com.difft.android.network.BaseResponse
import com.difft.android.network.group.ChangeSelfSettingsInGroupReq
import com.difft.android.network.group.GroupRepo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The group notification page re-reads this user's member row when its write ends and on every
 * resume. The server does not echo the change and the row otherwise updates only on the next
 * group sync, so an accepted change is stored in the row by [changeSelfNotification]; the page then
 * keeps showing it (a page restored after a fold too) instead of the stale value.
 */
class GroupSelfNotificationTest {

    /** The member row, stale until written: following the global default, notification 0. */
    private class RowStore : SelfNotificationStore {
        var row = SelfNotification(useGlobal = true, notification = 0)
        override fun read(groupId: String, myId: String): SelfNotification = row
        override fun write(groupId: String, myId: String, change: ChangeSelfSettingsInGroupReq) {
            row = SelfNotification(
                useGlobal = change.useGlobal ?: row.useGlobal,
                notification = change.notification ?: row.notification,
            )
        }
    }

    private val repo: GroupRepo = mockk()

    private fun respond(status: Int) {
        coEvery { repo.changeSelfSettingsInGroup(any(), any(), any()) } returns BaseResponse(ver = 1, status = status, reason = null, data = null)
    }

    @Test
    fun `an accepted switch is what the page reads back after the write and on resume`() {
        val store = RowStore()
        respond(status = 0)

        runBlocking { changeSelfNotification(repo, store, "g", "me", ChangeSelfSettingsInGroupReq(notification = null, useGlobal = false)) }

        // Both re-reads (key ended, resume) go through the store.
        assertEquals(SelfNotification(useGlobal = false, notification = 0), store.read("g", "me"))
    }

    @Test
    fun `an accepted explicit option is kept`() {
        val store = RowStore()
        respond(status = 0)

        runBlocking { changeSelfNotification(repo, store, "g", "me", ChangeSelfSettingsInGroupReq(notification = 2, useGlobal = false)) }

        assertEquals(SelfNotification(useGlobal = false, notification = 2), store.read("g", "me"))
    }

    @Test
    fun `a rejected change leaves the row as it was`() {
        val store = RowStore()
        respond(status = 1)

        runBlocking { changeSelfNotification(repo, store, "g", "me", ChangeSelfSettingsInGroupReq(notification = 2, useGlobal = false)) }

        assertEquals(SelfNotification(useGlobal = true, notification = 0), store.read("g", "me"))
    }
}
