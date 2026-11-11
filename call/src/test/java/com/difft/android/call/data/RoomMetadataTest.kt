package com.difft.android.call.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test






class RoomMetadataTest {

    
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun decode(raw: String) = json.decodeFromString<RoomMetadataPatch>(raw)

    
    private val restricted = RoomMetadata(
        callType = "group",
        canPublishAudio = false,
        canPublishVideo = false,
        canPublishScreen = false,
    )

    @Test
    fun `decodes a full payload`() {
        val patch = decode(
            """{"callType":"group","canPublishAudio":true,"canPublishScreen":false,"canPublishVideo":true}"""
        )

        assertEquals("group", patch.callType)
        assertEquals(true, patch.canPublishAudio)
        assertEquals(true, patch.canPublishVideo)
        assertEquals(false, patch.canPublishScreen)
    }

    
    @Test
    fun `decodes a payload without callType`() {
        val patch = decode("""{"canPublishAudio":true,"canPublishVideo":false}""")

        assertNull(patch.callType)
        assertEquals(true, patch.canPublishAudio)
        assertEquals(false, patch.canPublishVideo)
    }

    
    @Test
    fun `a missing publish flag does not discard callType`() {
        val patch = decode("""{"callType":"1on1"}""")

        assertEquals("1on1", patch.callType)
        assertNull(patch.canPublishAudio)
        assertNull(patch.canPublishVideo)
        assertNull(patch.canPublishScreen)
    }

    
    @Test
    fun `unknown keys are ignored`() {
        assertEquals("instant", decode("""{"callType":"instant","somethingNew":{"nested":1}}""").callType)
    }

    @Test
    fun `an empty object decodes to an all-absent patch`() {
        val patch = decode("{}")

        assertNull(patch.callType)
        assertNull(patch.canPublishAudio)
        assertNull(patch.canPublishVideo)
        assertNull(patch.canPublishScreen)
    }

    

    
    @Test
    fun `a callType-only update preserves existing publish restrictions`() {
        val merged = decode("""{"callType":"instant"}""").mergeInto(restricted)

        assertEquals("instant", merged.callType)
        assertFalse(merged.canPublishAudio)
        assertFalse(merged.canPublishVideo)
        assertFalse(merged.canPublishScreen)
    }

    @Test
    fun `an update without callType preserves the resolved one`() {
        val merged = decode("""{"canPublishAudio":true}""").mergeInto(restricted)

        assertEquals("group", merged.callType)
        assertTrue(merged.canPublishAudio)
        assertFalse(merged.canPublishVideo)
    }

    
    @Test
    fun `an explicitly sent restriction is applied`() {
        val permissive = RoomMetadata(callType = "group")

        val merged = decode("""{"canPublishAudio":false,"canPublishVideo":false}""").mergeInto(permissive)

        assertFalse(merged.canPublishAudio)
        assertFalse(merged.canPublishVideo)
        assertTrue(merged.canPublishScreen)
    }

    @Test
    fun `an empty payload changes nothing`() {
        assertEquals(restricted, decode("{}").mergeInto(restricted))
    }

    
    
    @Test
    fun `explicit nulls are treated as absent`() {
        val merged = decode("""{"callType":"1on1","canPublishAudio":null,"canPublishVideo":null}""")
            .mergeInto(restricted)

        assertEquals("1on1", merged.callType)
        assertFalse(merged.canPublishAudio)
        assertFalse(merged.canPublishVideo)
    }
}
