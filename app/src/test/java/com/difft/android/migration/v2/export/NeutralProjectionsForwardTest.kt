package com.difft.android.migration.v2.export

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
}
