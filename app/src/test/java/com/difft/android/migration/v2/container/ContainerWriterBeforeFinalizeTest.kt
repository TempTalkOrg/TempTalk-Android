package com.difft.android.migration.v2.container

import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralRecord
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue





@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class ContainerWriterBeforeFinalizeTest {

    private val crypto = BackupCryptoImpl()
    private val writer = ContainerWriterImpl(crypto)
    private val reader = ContainerReaderImpl(crypto)
    private val key = "ephemeral-transfer-key-256bit-material-b64=="

    private class MemorySink : ArchiveSink {
        val bytes = ByteArrayOutputStream()
        var aborted = false
        override fun openStream(): OutputStream = bytes
        override fun abort() {
            aborted = true
        }
    }

    private val records = (1..20).map { NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = "room-$it")) }

    private suspend fun write(sink: ArchiveSink, hook: suspend () -> Unit) = writer.write(
        records.asFlow(), sink, key, { null }, "acc", "1.0", beforeFinalize = hook,
    )

    private suspend fun readBack(bytes: ByteArray): Int {
        var count = 0
        reader.readStreaming(ByteArrayInputStream(bytes), key, { _, _ -> ByteArrayOutputStream() }) { count++ }
        return count
    }

    @Test
    fun `the hook runs once after the last record and the container stays valid`() = runTest {
        val sink = MemorySink()
        var calls = 0

        val manifest = write(sink) { calls++ }

        assertEquals(1, calls)
        assertEquals(records.size, manifest.recordCount)
        assertEquals(records.size, readBack(sink.bytes.toByteArray()))
    }

    @Test
    fun `a hook that throws leaves no committable container`() = runTest {
        val sink = MemorySink()

        assertFailsWith<Exception> { write(sink) { error("commit marker not written") } }

        assertTrue(sink.aborted)
        
        assertFailsWith<Exception> { readBack(sink.bytes.toByteArray()) }
    }
}
