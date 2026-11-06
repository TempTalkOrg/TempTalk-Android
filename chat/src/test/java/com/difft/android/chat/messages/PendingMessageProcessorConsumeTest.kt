package com.difft.android.chat.messages

import com.difft.android.test.fakes.TestMessagingPause
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.difft.app.database.PendingMessageStore
import org.difft.app.database.PendingRowKey
import org.difft.app.database.models.PendingMessageModelNew
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pending rows are consumed one by one by their opaque messageId (#1382): an `ios:` id from an
 * imported container and a live Android id behave alike, and a row that is not consumed survives
 * even when a sibling on the same target is.
 *
 * The fake store deletes exactly as `WcdbPendingMessageStore` does (by id plus loaded target, or
 * every row on a timestamp), so a processor that consumes by target timestamp again fails the
 * sibling cases, and one that consumes by id alone fails the re-saved-row case. The
 * WCDB predicates themselves are covered on a device by the microbenchmark module's
 * `PendingMessageStoreTest`.
 */
class PendingMessageProcessorConsumeTest {

    private val now = 1_800_000_000_000L
    private val target = now - 60_000L
    private val iosId = "ios:+10001:2:${now - 1_000L}:$target"
    private val androidId = "+10002:${now - 2_000L}:1"

    private val store = FakePendingMessageStore()
    private val messaging = TestMessagingPause().open()

    /** Each envelope timestamp maps to the result its processing returns. */
    private val results = mutableMapOf<Long, EnvelopeProcessResult>()

    /** What processing an envelope timestamp does to the store before it returns, e.g. re-saving its row. */
    private val sideEffects = mutableMapOf<Long, () -> Unit>()
    private val envelopes = mockk<EnvelopToMessageProcessor>().also { processor ->
        coEvery { processor.process(any(), any(), any()) } answers {
            val envelopeTs = firstArg<Envelope>().timestamp
            sideEffects[envelopeTs]?.invoke()
            results.getValue(envelopeTs)
        }
    }

    private fun processor() = PendingMessageProcessor(envelopes, store, messaging.gate) { now }

    private fun row(id: String, envelopeTs: Long, targetTs: Long = target) = PendingMessageModelNew().apply {
        messageId = id
        originalMessageTimeStamp = targetTs
        messageEnvelopBytes = Envelope.newBuilder().setTimestamp(envelopeTs).build().toByteArray()
    }

    private val success = EnvelopeProcessResult.Success(null)
    private val transient = EnvelopeProcessResult.TransientFailure(IllegalStateException("network"))
    private val permanent = EnvelopeProcessResult.PermanentFailure(DropReason.DECRYPTION_DATA_CORRUPT, null)

    @Test
    fun `a row whose target is present is applied and exactly that row is deleted`() = runBlocking {
        store.put(row(iosId, envelopeTs = 1L))
        store.put(row(androidId, envelopeTs = 2L, targetTs = target + 1)) // target not stored yet
        store.targets += target
        results[1L] = success

        processor().runAdmittedTick()

        assertEquals(setOf(androidId), store.ids())
        assertEquals(listOf(iosId), store.deletedIds)
        coVerify(exactly = 1) { envelopes.process(any(), any(), any()) }
    }

    @Test
    fun `a row waits for its target and applies once the target arrives`() = runBlocking {
        store.put(row(iosId, envelopeTs = 1L))
        results[1L] = success
        val processor = processor()

        processor.runAdmittedTick()
        assertEquals(setOf(iosId), store.ids())
        coVerify(exactly = 0) { envelopes.process(any(), any(), any()) }

        store.targets += target
        processor.runAdmittedTick()
        assertEquals(emptySet(), store.ids())
    }

    @Test
    fun `a transient sibling on the same target survives the successful row and is retried`() = runBlocking {
        store.put(row(iosId, envelopeTs = 1L))
        store.put(row(androidId, envelopeTs = 2L))
        store.targets += target
        results[1L] = success
        results[2L] = transient
        val processor = processor()

        processor.runAdmittedTick()
        assertEquals(setOf(androidId), store.ids())
        assertEquals(listOf(iosId), store.deletedIds)
        assertTrue(store.deletedTimestamps.isEmpty())

        results[2L] = success
        processor.runAdmittedTick()
        assertEquals(emptySet(), store.ids())
    }

    @Test
    fun `a row the replay re-saves under the same id for another target survives the tick`() = runBlocking {
        // A confidential sync-read envelope M covers [A, B]; both were missing, so M was saved twice
        // and the replace left M waiting on B. B arrives, A is still missing: replaying M re-saves it
        // for A (same messageId, new target) and handles B, returning Success.
        val readId = "+10004:${now - 3_000L}:1"
        val targetA = target - 5_000L
        store.put(row(readId, envelopeTs = 1L, targetTs = target))
        store.targets += target
        sideEffects[1L] = { store.put(row(readId, envelopeTs = 1L, targetTs = targetA)) }
        results[1L] = success

        processor().runAdmittedTick()

        assertEquals(setOf(readId), store.ids())
        assertEquals(targetA, store.loadAll().single().originalMessageTimeStamp)
    }

    @Test
    fun `a paused tick deletes nothing`() = runBlocking {
        store.put(row(iosId, envelopeTs = 1L))
        store.put(row(androidId, envelopeTs = 2L))
        store.targets += target
        results[1L] = EnvelopeProcessResult.Paused
        results[2L] = EnvelopeProcessResult.Paused

        processor().runAdmittedTick()

        assertEquals(setOf(iosId, androidId), store.ids())
        assertEquals(0, store.deleteCalls)
    }

    @Test
    fun `a permanent failure deletes only its own row`() = runBlocking {
        store.put(row(iosId, envelopeTs = 1L))
        store.put(row(androidId, envelopeTs = 2L))
        store.targets += target
        results[1L] = permanent
        results[2L] = transient

        processor().runAdmittedTick()

        assertEquals(setOf(androidId), store.ids())
        assertEquals(listOf(iosId), store.deletedIds)
    }

    @Test
    fun `bytes that do not parse are dropped without touching a sibling`() = runBlocking {
        store.put(row(iosId, envelopeTs = 1L).apply { messageEnvelopBytes = byteArrayOf(-1, -1, -1) })
        store.put(row(androidId, envelopeTs = 2L))
        store.targets += target
        results[2L] = transient

        processor().runAdmittedTick()

        assertEquals(setOf(androidId), store.ids())
    }

    @Test
    fun `rows survive a restart and apply on the new instance`() = runBlocking {
        store.put(row(iosId, envelopeTs = 1L))
        store.put(row(androidId, envelopeTs = 2L))
        store.targets += target
        results[1L] = transient
        results[2L] = transient

        processor().runAdmittedTick()
        assertEquals(setOf(iosId, androidId), store.ids())

        results[1L] = success
        results[2L] = success
        processor().runAdmittedTick() // a new process over the same table

        assertEquals(emptySet(), store.ids())
    }

    @Test
    fun `the expiry sweep removes only expired rows`() = runBlocking {
        val expiredTarget = now - 11L * 24 * 60 * 60 * 1000
        store.put(row(iosId, envelopeTs = 1L, targetTs = expiredTarget))
        store.put(row(androidId, envelopeTs = 2L))

        processor().runAdmittedTick()

        assertEquals(setOf(androidId), store.ids())
        assertEquals(listOf(expiredTarget), store.deletedTimestamps)
    }

    @Test
    fun `the owner drain consumes rows by id and reports what is left`() = runBlocking {
        store.put(row(iosId, envelopeTs = 1L))
        store.put(row(androidId, envelopeTs = 2L))
        store.targets += target
        results[1L] = success
        results[2L] = transient
        messaging.pauseMessaging()

        assertEquals(1, processor().drainAsOwner(TestMessagingPause.OWNER))
        assertEquals(setOf(androidId), store.ids())
    }

    @Test
    fun `only success and permanent failure consume a row`() {
        assertTrue(PendingMessageProcessor.isConsumed(success))
        assertTrue(PendingMessageProcessor.isConsumed(permanent))
        assertEquals(false, PendingMessageProcessor.isConsumed(transient))
        assertEquals(false, PendingMessageProcessor.isConsumed(EnvelopeProcessResult.Paused))
    }

    /** Deletes as `WcdbPendingMessageStore` does: by messageId, or every row on a target timestamp. */
    private class FakePendingMessageStore : PendingMessageStore {
        private val rows = linkedMapOf<String, PendingMessageModelNew>()
        val targets = mutableSetOf<Long>()
        val deletedIds = mutableListOf<String>()
        val deletedTimestamps = mutableListOf<Long>()
        var deleteCalls = 0

        fun put(row: PendingMessageModelNew) {
            rows[requireNotNull(row.messageId)] = row
        }

        fun ids(): Set<String> = rows.keys.toSet()

        override fun loadAll(): List<PendingMessageModelNew> = rows.values.toList()

        override fun existingTargetTimestamps(timestamps: Collection<Long>): Set<Long> =
            timestamps.filter { it in targets }.toSet()

        override fun deleteConsumed(keys: Collection<PendingRowKey>) {
            deleteCalls++
            deletedIds += keys.map { it.messageId }
            keys.forEach { key ->
                if (rows[key.messageId]?.originalMessageTimeStamp == key.originalMessageTimeStamp) rows.remove(key.messageId)
            }
        }

        override fun deleteByTargetTimestamps(timestamps: Collection<Long>) {
            deleteCalls++
            deletedTimestamps += timestamps
            rows.values.removeAll { it.originalMessageTimeStamp in timestamps }
        }

        override fun count(): Int = rows.size
    }
}
