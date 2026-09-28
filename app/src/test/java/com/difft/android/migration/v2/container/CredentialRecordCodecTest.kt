package com.difft.android.migration.v2.container

import com.difft.android.migration.v2.model.NeutralCredential
import com.difft.android.migration.v2.model.NeutralRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential record's wire shape. Two things are pinned here:
 *
 *  - the round trip, including the FROZEN `"credential"` tag — the tag can never change once a
 *    shipped build has written it;
 *  - the forward-compatibility contract every OTHER reader relies on: a tag a build does not know is
 *    reported and SKIPPED, not fatal. That is what lets a container carrying this record stay
 *    readable by an older build, and it is the second line of defence behind "a cross-device
 *    container never carries the record in the first place".
 */
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
        // Stands in for what an older build sees when it meets a tag added after it shipped.
        val unknown = """{"type":"somethingNewerShipped","value":{"a":1}}"""
        val seen = mutableListOf<String>()

        val decoded = codec.decodeRecord(unknown) { seen += it }

        assertNull("an unknown tag must be skipped, not decoded", decoded)
        assertEquals(listOf("somethingNewerShipped"), seen)
    }
}
