package com.difft.android.chat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Content-uri key resolution — the one place that must keep working for uris minted by ANY past
 * version of the app, since external apps and Glide hand them back long after they were created.
 *
 * The rows come from a fake so the assertions pin the ORDER, which is the whole contract.
 */
class AttachmentUriRowLookupTest {

    private data class Row(val label: String, val fileName: String?, val synthesizedLocalId: String = "")

    private fun lookupOf(
        byLocalId: List<Row> = emptyList(),
        byMessageId: List<Row> = emptyList(),
        byAuthorityId: List<Row> = emptyList(),
        bySynthesizedLocalId: List<Row> = emptyList()
    ): (AttachmentUriIdentity, String) -> List<Row> = { identity, _ ->
        when (identity) {
            AttachmentUriIdentity.LOCAL_ID -> byLocalId
            AttachmentUriIdentity.MESSAGE_ID -> byMessageId
            AttachmentUriIdentity.AUTHORITY_ID -> byAuthorityId
            AttachmentUriIdentity.SYNTHESIZED_LOCAL_ID -> bySynthesizedLocalId
        }
    }

    private fun resolve(key: String, lookup: (AttachmentUriIdentity, String) -> List<Row>): Row? =
        resolveAttachmentRowByUriKey(
            key = key,
            fileName = "photo.jpg",
            fileNameOf = { it.fileName },
            synthesizedLocalIdOf = { it.synthesizedLocalId },
            lookup = lookup
        )

    @Test
    fun `a localId segment resolves to the per-copy row`() {
        val row = resolve("local-1", lookupOf(byLocalId = listOf(Row("local", "photo.jpg"))))

        assertEquals("local", row?.label)
    }

    @Test
    fun `a historic messageId segment still resolves`() {
        val row = resolve("msg-1", lookupOf(byMessageId = listOf(Row("message", "photo.jpg"))))

        assertEquals("message", row?.label)
    }

    @Test
    fun `a historic numeric authorityId segment still resolves`() {
        val row = resolve("987", lookupOf(byAuthorityId = listOf(Row("authority", "photo.jpg"))))

        assertEquals("authority", row?.label)
    }

    @Test
    fun `localId wins over the older interpretations of the same segment`() {
        val row = resolve(
            "987",
            lookupOf(
                byLocalId = listOf(Row("local", "photo.jpg")),
                byMessageId = listOf(Row("message", "photo.jpg")),
                byAuthorityId = listOf(Row("authority", "photo.jpg"))
            )
        )

        assertEquals("local", row?.label)
    }

    @Test
    fun `messageId wins over authorityId when localId misses`() {
        val row = resolve(
            "987",
            lookupOf(
                byMessageId = listOf(Row("message", "photo.jpg")),
                byAuthorityId = listOf(Row("authority", "photo.jpg"))
            )
        )

        assertEquals("message", row?.label)
    }

    @Test
    fun `a non-numeric segment never reaches the authorityId lookup`() {
        var authorityQueried = false
        val row = resolve("not-a-number") { identity, _ ->
            if (identity == AttachmentUriIdentity.AUTHORITY_ID) authorityQueried = true
            emptyList()
        }

        assertNull(row)
        assertEquals(false, authorityQueried)
    }

    @Test
    fun `within one identity the file-name match wins over row order`() {
        val row = resolve(
            "local-1",
            lookupOf(byLocalId = listOf(Row("other", "video.mp4"), Row("wanted", "photo.jpg")))
        )

        assertEquals("wanted", row?.label)
    }

    @Test
    fun `a row with no usable file name is still returned, preserving the legacy fallback`() {
        val row = resolve("local-1", lookupOf(byLocalId = listOf(Row("nameless", null))))

        assertEquals("nameless", row?.label)
    }

    @Test
    fun `an empty segment resolves to nothing`() {
        assertNull(resolve("", lookupOf(byLocalId = listOf(Row("local", "photo.jpg")))))
    }

    @Test
    fun `a pre-backfill forward uri resolves by the id synthesized from its row`() {
        // The window before the backfill runs: the row's localId column is NULL and a forward row
        // carries no messageId, so only the synthesized id can name it — and the uri was minted
        // under exactly that id.
        val row = resolve(
            SYNTHESIZED,
            lookupOf(
                bySynthesizedLocalId = listOf(
                    Row("other-row", "photo.jpg", synthesizedLocalId = OTHER_SYNTHESIZED),
                    Row("wanted", "photo.jpg", synthesizedLocalId = SYNTHESIZED)
                )
            )
        )

        assertEquals("wanted", row?.label)
    }

    @Test
    fun `the synthesized fallback matches exactly, never by file name alone`() {
        val row = resolve(
            SYNTHESIZED,
            lookupOf(bySynthesizedLocalId = listOf(Row("same-name-different-row", "photo.jpg", OTHER_SYNTHESIZED)))
        )

        assertNull(row)
    }

    @Test
    fun `a persisted localId never pays for the synthesized scan`() {
        var synthesizedQueried = false
        val row = resolveAttachmentRowByUriKey(
            key = SYNTHESIZED,
            fileName = "photo.jpg",
            fileNameOf = { it: Row -> it.fileName },
            synthesizedLocalIdOf = { it.synthesizedLocalId },
            lookup = { identity, _ ->
                if (identity == AttachmentUriIdentity.SYNTHESIZED_LOCAL_ID) synthesizedQueried = true
                if (identity == AttachmentUriIdentity.LOCAL_ID) listOf(Row("local", "photo.jpg")) else emptyList()
            }
        )

        assertEquals("local", row?.label)
        assertEquals(false, synthesizedQueried)
    }

    @Test
    fun `a segment that is not UUID-shaped never pays for the synthesized scan`() {
        var synthesizedQueried = false
        val row = resolve("not-a-number") { identity, _ ->
            if (identity == AttachmentUriIdentity.SYNTHESIZED_LOCAL_ID) synthesizedQueried = true
            emptyList()
        }

        assertNull(row)
        assertEquals(false, synthesizedQueried)
    }

    private companion object {
        /** Shape of `UUID.nameUUIDFromBytes(...)`, which is what a synthesized local id is. */
        const val SYNTHESIZED = "6d5f2f4b-9f3a-3a1c-8b0e-0f1a2b3c4d5e"
        const val OTHER_SYNTHESIZED = "11112222-3333-4444-5555-666677778888"
    }
}
