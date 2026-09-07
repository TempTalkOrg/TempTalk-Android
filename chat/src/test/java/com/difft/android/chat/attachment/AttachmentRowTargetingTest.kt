package com.difft.android.chat.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which attachment rows a write can reach.
 *
 * This is the exact shape of issue #1178: forwarding copied a message's attachment row with the SAME
 * server-side id, so a status write locating rows by that id updated the original message's row too
 * and its bubble replayed the copy's download. A localId locator makes the sibling unreachable.
 */
class AttachmentRowTargetingTest {

    private data class Row(val label: String, val messageId: String?, val authorityId: Long?)

    @Test
    fun `a copy with a localId is located by it, so a sibling sharing the server id is out of reach`() {
        val target = attachmentRowTarget(localId = "local-copy", attachmentId = "shared-id", messageId = "msg-1")

        assertEquals(AttachmentRowTarget.ByLocalId("local-copy"), target)
    }

    @Test
    fun `two copies of one file target two different rows`() {
        val original = attachmentRowTarget("local-original", "shared-id", "msg-1")
        val forwarded = attachmentRowTarget("local-forwarded", "shared-id", "msg-2")

        // Same server-side id on both — only the localId tells the rows apart.
        assertEquals(AttachmentRowTarget.ByLocalId("local-original"), original)
        assertEquals(AttachmentRowTarget.ByLocalId("local-forwarded"), forwarded)
    }

    @Test
    fun `a legacy job without a localId degrades to the id and message pair`() {
        val target = attachmentRowTarget(localId = null, attachmentId = "shared-id", messageId = "msg-1")

        assertEquals(AttachmentRowTarget.ByIdAndMessage("shared-id", "msg-1"), target)
    }

    @Test
    fun `an empty localId is treated as absent, never as a key`() {
        val target = attachmentRowTarget(localId = "", attachmentId = "shared-id", messageId = "msg-1")

        assertEquals(AttachmentRowTarget.ByIdAndMessage("shared-id", "msg-1"), target)
    }

    @Test
    fun `with neither a localId nor a message id only the server id is left`() {
        assertEquals(AttachmentRowTarget.ById("shared-id"), attachmentRowTarget(null, "shared-id", null))
        assertEquals(AttachmentRowTarget.ById("shared-id"), attachmentRowTarget(null, "shared-id", ""))
    }

    @Test
    fun `the legacy locator cannot reach a forward-owned row at all`() {
        // A forward copy's `messageId` column is NULL — the owning message id lives on its
        // ForwardModel — so the id+messageId predicate a job persisted before the localId key
        // degrades to matches nothing, and its status write silently vanishes. That is why
        // DownloadAttachmentJob re-addresses by the resolved row's rowid once it has one.
        val forwardRow = Row("forward-copy", messageId = null, authorityId = 999L)
        val target = attachmentRowTarget(localId = null, attachmentId = "shared-id", messageId = "msg-wrapper")

        val reached = listOf(forwardRow).filter {
            target is AttachmentRowTarget.ByIdAndMessage && it.messageId == target.messageId
        }
        assertEquals(emptyList<Row>(), reached)
    }

    @Test
    fun `an unambiguous legacy lookup takes the only row`() {
        val rows = listOf(Row("only", "msg-1", 777L))

        assertEquals("only", pick(rows, legacyKey = "anything")?.label)
        assertNull(pick(emptyList(), legacyKey = "msg-1"))
    }

    @Test
    fun `an ambiguous legacy lookup prefers the row its owner message names`() {
        val rows = listOf(Row("forward-copy", null, 777L), Row("original", "msg-1", 777L))

        assertEquals("original", pick(rows, legacyKey = "msg-1")?.label)
    }

    @Test
    fun `a legacy single-forward job resolves through the authority id it was keyed by`() {
        val rows = listOf(Row("original", "msg-1", 777L), Row("forward-copy", null, 999L))

        assertEquals("forward-copy", pick(rows, legacyKey = "999")?.label)
    }

    @Test
    fun `an unidentifiable legacy job still picks a row rather than giving up`() {
        val rows = listOf(Row("first", "msg-1", 777L), Row("second", "msg-2", 777L))

        // Every candidate holds the same remote file, so the worst case is a redundant download.
        assertEquals("first", pick(rows, legacyKey = "msg-gone")?.label)
    }

    @Test
    fun `a legacy job adopts the local id of the one row it named`() {
        val rows = listOf(LocalIdRow(localId = "local-1", synthesized = "synth-1"))

        assertEquals("local-1", adopt(rows))
    }

    @Test
    fun `a row with no local id yet answers with the id every reader synthesizes for it`() {
        // Same value the migration's backfill persists, so the key the job emits under is exactly
        // the one the rendering bubble collects under.
        assertEquals("synth-1", adopt(listOf(LocalIdRow(localId = null, synthesized = "synth-1"))))
        assertEquals("synth-1", adopt(listOf(LocalIdRow(localId = "", synthesized = "synth-1"))))
    }

    @Test
    fun `an ambiguous legacy match adopts nothing`() {
        // Writing an id into one of several rows sharing a server-side id would hand a sibling copy
        // an identity it was never rendered under.
        val rows = listOf(LocalIdRow(null, "synth-1"), LocalIdRow(null, "synth-2"))

        assertNull(adopt(rows))
        assertNull(adopt(emptyList()))
    }

    private data class LocalIdRow(val localId: String?, val synthesized: String)

    private fun adopt(rows: List<LocalIdRow>): String? =
        adoptableLocalId(rows, { it.localId }, { it.synthesized })

    private fun pick(rows: List<Row>, legacyKey: String): Row? =
        pickLegacyAttachmentRow(rows, legacyKey, { it.messageId }, { it.authorityId })
}
