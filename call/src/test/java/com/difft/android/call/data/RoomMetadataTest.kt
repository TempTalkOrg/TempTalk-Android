package com.difft.android.call.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode and merge coverage for [RoomMetadataPatch].
 *
 * Two properties are load-bearing here. The decode has to survive partial payloads: before
 * `callType` was added the model required both publish flags to be present, so a payload missing
 * either one threw and the whole metadata — the new `callType` included — was discarded. And an
 * absent key has to leave the current value alone rather than fall back to a default, because the
 * publish flags gate the mic and camera in `CallMediaController`; defaulting them to `true` would
 * let a `callType`-only update re-grant a restriction the server had imposed.
 */
class RoomMetadataTest {

    /** Must mirror the instance built in `LCallViewModel`. */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun decode(raw: String) = json.decodeFromString<RoomMetadataPatch>(raw)

    /** Server has restricted publishing — the state an incoming patch must not silently undo. */
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

    // The pre-existing shape: a room created before callType shipped must still decode, and must
    // report a null callType so the local decision keeps running instead of being reset.
    @Test
    fun `decodes a payload without callType`() {
        val patch = decode("""{"canPublishAudio":true,"canPublishVideo":false}""")

        assertNull(patch.callType)
        assertEquals(true, patch.canPublishAudio)
        assertEquals(false, patch.canPublishVideo)
    }

    // The regression that motivated making every field optional: a missing publish flag used to fail
    // the whole decode and take callType down with it.
    @Test
    fun `a missing publish flag does not discard callType`() {
        val patch = decode("""{"callType":"1on1"}""")

        assertEquals("1on1", patch.callType)
        assertNull(patch.canPublishAudio)
        assertNull(patch.canPublishVideo)
        assertNull(patch.canPublishScreen)
    }

    // Server keys we do not model yet must not fail the decode.
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

    // ---------------------------------------------------------------------------------
    // Merge semantics: absent keys keep the value already in effect.
    // ---------------------------------------------------------------------------------

    // The reason the patch type exists: a callType-only update must not re-grant publishing.
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

    // A restriction the server does send must of course still be applied.
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

    // coerceInputValues is configured on the production Json instance; for these nullable fields an
    // explicit null is simply "absent", which must not clear an active restriction either.
    @Test
    fun `explicit nulls are treated as absent`() {
        val merged = decode("""{"callType":"1on1","canPublishAudio":null,"canPublishVideo":null}""")
            .mergeInto(restricted)

        assertEquals("1on1", merged.callType)
        assertFalse(merged.canPublishAudio)
        assertFalse(merged.canPublishVideo)
    }
}
