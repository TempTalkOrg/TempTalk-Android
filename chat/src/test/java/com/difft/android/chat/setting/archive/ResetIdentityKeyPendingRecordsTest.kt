package com.difft.android.chat.setting.archive

import com.difft.android.websocket.api.messages.PublicKeyInfo
import org.difft.app.database.models.ResetIdentityKeyModel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cold-start `/keys?beginTimestamp=` reconciliation in [MessageArchiveManager.pendingResetIdentityKeyRecords].
 * The guard does not depend on whether the endpoint echoes the boundary record back (observed: it
 * does not): a record already processed for the same reset time is skipped either way, while a later
 * reset by the same user must still be re-queued.
 */
class ResetIdentityKeyPendingRecordsTest {

    private fun remote(uid: String, resetTime: Long) = PublicKeyInfo(uid, "key", 1, resetTime)

    private fun local(uid: String, resetTime: Long, status: Int) = ResetIdentityKeyModel().apply {
        this.uid = uid
        this.resetTime = resetTime
        this.status = status
    }

    @Test
    fun `unknown user is queued as pending`() {
        val pending = MessageArchiveManager.pendingResetIdentityKeyRecords(emptyMap(), listOf(remote("+1", 100L)))

        assertEquals(1, pending.size)
        assertEquals("+1", pending[0].uid)
        assertEquals(100L, pending[0].resetTime)
        assertEquals(0, pending[0].status)
    }

    @Test
    fun `processed record echoed back with the same reset time is not re-queued`() {
        val known = mapOf<String?, ResetIdentityKeyModel>("+1" to local("+1", 100L, status = 1))

        val pending = MessageArchiveManager.pendingResetIdentityKeyRecords(known, listOf(remote("+1", 100L)))

        assertTrue(pending.isEmpty())
    }

    @Test
    fun `later reset by a processed user is queued again`() {
        val known = mapOf<String?, ResetIdentityKeyModel>("+1" to local("+1", 100L, status = 1))

        val pending = MessageArchiveManager.pendingResetIdentityKeyRecords(known, listOf(remote("+1", 200L)))

        assertEquals(listOf(200L), pending.map { it.resetTime })
        assertEquals(0, pending[0].status)
    }

    @Test
    fun `interrupted record stays pending when echoed back`() {
        val known = mapOf<String?, ResetIdentityKeyModel>("+1" to local("+1", 100L, status = 0))

        val pending = MessageArchiveManager.pendingResetIdentityKeyRecords(known, listOf(remote("+1", 100L)))

        assertEquals(1, pending.size)
        assertEquals(0, pending[0].status)
    }
}
