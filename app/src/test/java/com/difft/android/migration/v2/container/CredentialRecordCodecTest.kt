package com.difft.android.migration.v2.container

import com.difft.android.migration.v2.model.NeutralCredential
import com.difft.android.migration.v2.model.NeutralRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test











class CredentialRecordCodecTest {

    private val codec = RecordJsonCodec()

    private val credential = NeutralCredential(
        account = "+12025550100",
        baseAuth = "base-auth",
        microToken = "micro-token",
        signalingKey = "signaling-key",
        email = "a@b.c",
        phoneNumber = "+12025550100",
        handoffId = "handoff-1",
    )

    @Test
    fun `a credential record round-trips every field under the frozen tag`() {
        val line = codec.encodeRecord(NeutralRecord.Credential(credential))
        assertTrue("envelope must carry the frozen tag", line.contains("\"type\":\"credential\""))

        val decoded = codec.decodeRecord(line)
        assertTrue(decoded is NeutralRecord.Credential)
        assertEquals(credential, (decoded as NeutralRecord.Credential).value)
    }

    @Test
    fun `the optional contact fields round-trip as null when absent`() {
        val bare = credential.copy(email = null, phoneNumber = null)
        val decoded = codec.decodeRecord(codec.encodeRecord(NeutralRecord.Credential(bare)))
        assertEquals(bare, (decoded as NeutralRecord.Credential).value)
    }

    @Test
    fun `an unknown record tag is reported and skipped, never fatal`() {
        
        val unknown = """{"type":"somethingNewerShipped","value":{"a":1}}"""
        val seen = mutableListOf<String>()

        val decoded = codec.decodeRecord(unknown) { seen += it }

        assertNull("an unknown tag must be skipped, not decoded", decoded)
        assertEquals(listOf("somethingNewerShipped"), seen)
    }
}
