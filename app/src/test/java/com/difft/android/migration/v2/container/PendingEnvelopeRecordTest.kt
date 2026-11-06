package com.difft.android.migration.v2.container

import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralPendingEnvelope
import com.difft.android.migration.v2.model.NeutralRecord
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The `pendingEnvelope` record (#1382): its frozen wire shape, the strict decode, and the reader
 * failing the import instead of skipping a pending envelope that does not decode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class PendingEnvelopeRecordTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val codec = RecordJsonCodec()
    private val crypto = BackupCryptoImpl()
    private val transferKey = "ephemeral-transfer-key-256bit-material-b64=="

    private val bytes = byteArrayOf(8, 1, 16, 42, 0x7f, -1)
    private val envelope = NeutralPendingEnvelope(
        messageId = "1700000000123u1",
        originalMessageTimeStamp = 1_700_000_000_000L,
        messageEnvelopBytes = Base64.getEncoder().encodeToString(bytes),
    )

    @Test
    fun `a pending envelope round-trips under the frozen tag and field names`() {
        val line = codec.encodeRecord(NeutralRecord.PendingEnvelope(envelope))

        val json = JsonParser.parseString(line).asJsonObject
        assertEquals("pendingEnvelope", json["type"].asString)
        val value = json["value"].asJsonObject
        assertEquals(setOf("messageId", "originalMessageTimeStamp", "messageEnvelopBytes"), value.keySet())
        assertEquals(1_700_000_000_000L, value["originalMessageTimeStamp"].asLong)
        assertTrue(bytes.contentEquals(Base64.getDecoder().decode(value["messageEnvelopBytes"].asString)))

        assertEquals(envelope, assertIs<NeutralRecord.PendingEnvelope>(codec.decodeRecord(line)).value)
    }

    @Test
    fun `a pending envelope that does not decode must not be skipped`() {
        val malformed = listOf(
            """{"messageId":"","originalMessageTimeStamp":1,"messageEnvelopBytes":"CAE="}""",
            """{"originalMessageTimeStamp":1,"messageEnvelopBytes":"CAE="}""",
            """{"messageId":"m","originalMessageTimeStamp":0,"messageEnvelopBytes":"CAE="}""",
            """{"messageId":"m","originalMessageTimeStamp":1}""",
            """{"messageId":"m","originalMessageTimeStamp":1,"messageEnvelopBytes":""}""",
            """{"messageId":"m","originalMessageTimeStamp":1,"messageEnvelopBytes":"not base64!"}""",
        )
        malformed.forEach { value ->
            val decoded = codec.decode("""{"type":"pendingEnvelope","value":$value}""")
            val skipped = assertIs<RecordJsonCodec.Decoded.Skipped>(decoded, value)
            assertTrue(skipped.mustNotSkip, value)
        }
        // Other records keep their tolerance.
        val other = codec.decode("""{"type":"groupKey","value":"not an object"}""")
        assertFalse(assertIs<RecordJsonCodec.Decoded.Skipped>(other).mustNotSkip)
    }

    @Test
    fun `the reader streams a pending envelope to the importer`() = runTest {
        val received = ArrayList<NeutralRecord>()

        ContainerReaderImpl(crypto).readStreaming(ByteArrayInputStream(container(envelope)), transferKey, discard()) { received += it }

        assertEquals(listOf(envelope), received.filterIsInstance<NeutralRecord.PendingEnvelope>().map { it.value })
    }

    @Test
    fun `a pending envelope that does not decode fails the read`() = runTest {
        val bad = envelope.copy(messageEnvelopBytes = "%%% not base64 %%%")
        val received = ArrayList<NeutralRecord>()

        assertFailsWith<MigrationException.DecryptionFailed> {
            ContainerReaderImpl(crypto).readStreaming(ByteArrayInputStream(container(bad)), transferKey, discard()) { received += it }
        }
        assertTrue(received.none { it is NeutralRecord.PendingEnvelope })
    }

    private suspend fun container(pending: NeutralPendingEnvelope): ByteArray {
        val file = File(tmp.root, "c-${System.nanoTime()}.ttmig")
        val records = listOf(
            NeutralRecord.PendingEnvelope(pending),
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = "room-1")),
        )
        ContainerWriterImpl(crypto).write(
            records.asFlow(), FileArchiveSink(file), transferKey, AttachmentByteSource { null }, "u_pending", "0.0.0-pending",
        )
        return file.readBytes()
    }

    private fun discard() = AttachmentByteTarget { _, _ -> null }
}
