package com.difft.android.migration.v2.export

import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.ForwardModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A forward node's portable sentAt is the forwarded message's own sentAt — the wire `Forward.id` —
 * not the server timestamp, which is often 0 on a sub-node and made iOS refuse the whole message.
 */
class NeutralProjectionsForwardTest {

    @Test
    fun `forward sentAt is the node's id, and serverTimestamp only when the id was never set`() {
        val both = ForwardModel().apply { id = 1_700_000_000_123L; serverTimestamp = 1_700_000_000_999L }
        assertEquals(1_700_000_000_123L, NeutralProjections.forwardSentAt(both))

        val idOnly = ForwardModel().apply { id = 1_700_000_000_123L; serverTimestamp = 0L }
        assertEquals(1_700_000_000_123L, NeutralProjections.forwardSentAt(idOnly))

        val legacyRow = ForwardModel().apply { id = 0L; serverTimestamp = 1_700_000_000_999L }
        assertEquals(1_700_000_000_999L, NeutralProjections.forwardSentAt(legacyRow))
    }

    @Test
    fun `attachment meta names its byte record by the same stableId rule, falling back to authorityId`() {
        val withId = AttachmentModel().apply { id = "att-1"; authorityId = 700L }
        assertEquals("att-1", NeutralProjections.attachmentMeta(withId).stableId)

        // A forward row that arrived from another platform has no `id`; the byte record is keyed by
        // authorityId, so the preview meta must say the same or it names nothing.
        val idless = AttachmentModel().apply { id = null; authorityId = 123456L }
        assertEquals("123456", NeutralProjections.attachmentMeta(idless).stableId)
        assertEquals(NeutralProjections.attachmentStableRef(idless), NeutralProjections.attachmentMeta(idless).stableId)

        val neither = AttachmentModel().apply { id = null; authorityId = 0L }
        assertEquals("", NeutralProjections.attachmentMeta(neither).stableId)
    }
}
