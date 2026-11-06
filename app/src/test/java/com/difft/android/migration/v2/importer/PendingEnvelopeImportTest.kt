package com.difft.android.migration.v2.importer

import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.InMemoryWcdbWriteTarget
import com.difft.android.migration.v2.model.NeutralPendingEnvelope
import com.difft.android.migration.v2.model.NeutralRecord
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A pending envelope (#1382) lands in the target's `pending_message_new` exactly as the source held
 * it, in its own transaction, and a row that cannot be stored fails the import rather than being
 * counted and skipped like other records.
 */
class PendingEnvelopeImportTest {

    private val bytes = byteArrayOf(8, 1, 16, 42)

    private fun record(id: String = "recall-1", text: String = Base64.getEncoder().encodeToString(bytes)) =
        NeutralRecord.PendingEnvelope(NeutralPendingEnvelope(id, 1_700_000_000_000L, text))

    private fun importerOver(target: WcdbWriteTarget) = NeutralImporter(target, messageAttachmentDir = { "/t/$it/" })

    @Test
    fun `a pending envelope is written to the pending table`() {
        val target = InMemoryWcdbWriteTarget()
        val importer = importerOver(target)

        importer.onRecord(record())
        importer.onRecord(record(id = "reaction-2"))
        importer.finish()

        assertEquals(listOf("recall-1", "reaction-2"), target.pendingEnvelopes.map { it.messageId })
        val row = target.pendingEnvelopes.first()
        assertEquals(1_700_000_000_000L, row.originalMessageTimeStamp)
        assertTrue(bytes.contentEquals(row.messageEnvelopBytes))
    }

    @Test
    fun `an iOS-format id is stored unchanged as the opaque row key`() {
        val iosId = "ios:+10001:2:1700000000500:1700000000000"
        val target = InMemoryWcdbWriteTarget()
        val importer = importerOver(target)

        importer.onRecord(record(id = iosId))
        importer.finish()

        val row = target.pendingEnvelopes.single()
        assertEquals(iosId, row.messageId)
        assertEquals(1_700_000_000_000L, row.originalMessageTimeStamp)
        assertTrue(bytes.contentEquals(row.messageEnvelopBytes))
    }

    @Test
    fun `a pending envelope that cannot be stored fails the import`() {
        val target = InMemoryWcdbWriteTarget().apply { failPendingEnvelopeWrites = true }

        assertFailsWith<MigrationException.RestoreFailed> { importerOver(target).onRecord(record()) }
        assertTrue(target.pendingEnvelopes.isEmpty())
    }

    @Test
    fun `pending envelope bytes that are not base64 fail the import`() {
        val target = InMemoryWcdbWriteTarget()

        assertFailsWith<MigrationException.RestoreFailed> { importerOver(target).onRecord(record(text = "%%%")) }
        assertTrue(target.pendingEnvelopes.isEmpty())
    }
}
