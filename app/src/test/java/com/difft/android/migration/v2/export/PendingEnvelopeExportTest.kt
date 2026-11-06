package com.difft.android.migration.v2.export

import org.difft.app.database.models.PendingMessageModelNew
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which pending rows a device replacement carries (#1382): the ones the receive path would still
 * apply — unexpired by the 10-day rule on the target's timestamp, with bytes that parse.
 */
class PendingEnvelopeExportTest {

    private val now = 1_800_000_000_000L

    private fun row(id: String?, originalTs: Long, bytes: ByteArray = envelope(originalTs)) = PendingMessageModelNew().apply {
        messageId = id
        originalMessageTimeStamp = originalTs
        messageEnvelopBytes = bytes
    }

    private fun envelope(ts: Long): ByteArray = Envelope.newBuilder().setTimestamp(ts + 5).build().toByteArray()

    @Test
    fun `only unexpired rows with a parseable envelope are carried, verbatim`() {
        val justInside = now - TimeUnit.DAYS.toMillis(10)
        val rows = listOf(
            row("live", now - TimeUnit.DAYS.toMillis(3)),
            row("edge", justInside),
            row("expired", justInside - 1),
            row("corrupt", now, bytes = byteArrayOf(0, 0, 0)),
            row("empty", now, bytes = ByteArray(0)),
            row(null, now),
        )

        val carried = PendingEnvelopeExport.select(rows, now).map { it.value }

        assertEquals(listOf("live", "edge"), carried.map { it.messageId })
        assertEquals(justInside, carried[1].originalMessageTimeStamp)
        assertTrue(envelope(justInside).contentEquals(Base64.getDecoder().decode(carried[1].messageEnvelopBytes)))
    }

    @Test
    fun `nothing to carry from an empty or all-expired table`() {
        assertTrue(PendingEnvelopeExport.select(emptyList(), now).isEmpty())
        assertTrue(PendingEnvelopeExport.select(listOf(row("old", now - TimeUnit.DAYS.toMillis(30))), now).isEmpty())
    }
}
