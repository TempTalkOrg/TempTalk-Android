package com.difft.android.chat.group

import com.difft.android.network.BaseResponse
import com.difft.android.network.group.ChangeSelfSettingsInGroupReq
import com.difft.android.network.group.GroupRepo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test







class GroupSelfNotificationTest {

    
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
