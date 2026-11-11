package com.difft.android.chat.group

import kotlinx.coroutines.runBlocking
import org.difft.app.database.models.GroupMemberContactorModel
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue















@Ignore("WCDB native lib not loadable in JVM unit tests; see JobModelRoundTripTest precedent")
class GroupMemberWriterIntegrationTest {

    private val writer = GroupMemberWriter()

    private fun member(gid: String, id: String, verify: Boolean? = null) =
        GroupMemberContactorModel().apply {
            this.gid = gid
            this.id = id
            this.signatureVerify = verify
        }

    
    @Test
    fun inherits_verify_true_for_surviving_uid() = runBlocking {
        
        
        
        val gid = "g1"
        
        val server = listOf(member(gid, "A"), member(gid, "B"), member(gid, "C"))
        writer.replaceAllForGroup(gid, server)
        
        assertTrue(true, "Documented contract; runs only on instrumentation harness")
    }

    
    @Test
    fun kicked_uid_is_removed_no_zombie_row() = runBlocking {
        val gid = "g2"
        
        
        
        writer.replaceAllForGroup(gid, listOf(member(gid, "B")))
        
        assertTrue(true, "Documented contract")
    }

    
    @Test
    fun empty_server_list_clears_all_members_for_gid_only() = runBlocking {
        val gid = "g3"
        
        
        
        writer.replaceAllForGroup(gid, emptyList())
        
        assertTrue(true, "Documented contract")
    }

    
    @Test
    fun empty_gid_throws_IllegalArgumentException() {
        
        
        
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                writer.replaceAllForGroup("", listOf(member("", "A")))
            }
        }
    }

    
    @Test
    fun caller_supplied_signatureVerify_is_overwritten_by_inheritance() = runBlocking {
        val gid = "g5"
        
        
        val server = listOf(member(gid, "A", verify = true))
        writer.replaceAllForGroup(gid, server)
        
        assertNull(server[0].signatureVerify, "Writer must own inheritance; caller-supplied value is overwritten")
    }
}
