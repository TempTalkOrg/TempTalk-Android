package com.difft.android.chat.ui

import com.difft.android.messageserialization.db.store.DraftRepository
import difft.android.messageserialization.model.Draft
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Single writer for conversation drafts. The debounced save and [DraftViewModel.flushNow] (and
 * a ViewModel being cleared while another opens the same room) can write concurrently, and
 * [DraftRepository.upsertDraft] is a read-then-insert without a version. Every write takes a
 * version when the draft is produced ([nextVersion]) and runs under one lock; a write older than
 * the last one applied to that room is dropped, so an older draft never replaces a newer one.
 */
internal class DraftWriter(
    private val repository: DraftRepository,
    private val order: Order = Order.shared,
) {
    /** Lock and per-room versions shared by every writer in the process. */
    class Order {
        internal val lock = Mutex()
        internal val counter = AtomicLong()
        internal val applied = HashMap<String, Long>()

        companion object {
            val shared = Order()
        }
    }

    fun nextVersion(): Long = order.counter.incrementAndGet()

    suspend fun upsert(roomId: String, draft: Draft, version: Long) = ordered(roomId, version) {
        repository.upsertDraft(roomId, draft)
    }

    suspend fun clear(roomId: String, version: Long) = ordered(roomId, version) {
        repository.clearDraft(roomId)
    }

    private suspend fun ordered(roomId: String, version: Long, write: () -> Unit) {
        order.lock.withLock {
            if (version <= (order.applied[roomId] ?: 0L)) return
            order.applied[roomId] = version
            write()
        }
    }
}
