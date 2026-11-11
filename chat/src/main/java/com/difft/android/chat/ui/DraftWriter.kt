package com.difft.android.chat.ui

import com.difft.android.messageserialization.db.store.DraftRepository
import difft.android.messageserialization.model.Draft
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong








internal class DraftWriter(
    private val repository: DraftRepository,
    private val order: Order = Order.shared,
) {
    
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
