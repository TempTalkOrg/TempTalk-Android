package com.difft.android.test.fakes

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory `DataStore<Preferences>` for tests that need to observe reads and writes, whether the
 * writer runs on a test dispatcher or on the real `appScope`.
 *
 * @param initial first emission of [data]; pass `null` for a store that stays silent until [emit],
 *   which models a disk that never answers.
 */
class RecordingPreferencesDataStore(initial: Preferences? = emptyPreferences()) : DataStore<Preferences> {

    // Latest-value semantics: a new emission replaces the replayed one even while a subscriber is
    // still suspended, so emit() never fails.
    private val emissions = MutableSharedFlow<Preferences>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val writes = LinkedBlockingQueue<Preferences>()
    private val writeLock = Mutex()

    /** Number of times [data] was requested. */
    val reads = AtomicInteger(0)

    /** Number of committed writes so far; unlike [awaitWrite], reading it consumes nothing. */
    val writeCount = AtomicInteger(0)

    init {
        initial?.let(::emit)
    }

    override val data: Flow<Preferences>
        get() {
            reads.incrementAndGet()
            return emissions
        }

    /**
     * Serialized like the real DataStore actor, so concurrent writers cannot lose each other's keys,
     * and — also like the real one — a write waits for the first read to complete: with
     * `initial = null` it suspends until [emit] supplies the disk value.
     */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writeLock.withLock {
            val updated = transform(emissions.first())
            emit(updated)
            writeCount.incrementAndGet()
            writes.put(updated)
            updated
        }

    /** Simulates the disk (initial load or an external change); call from the test thread, not concurrently with a write. */
    fun emit(prefs: Preferences) = check(emissions.tryEmit(prefs)) { "replay=1 shared flow must accept emit" }

    /** Latest committed write, for writers on a real dispatcher; fails after 5 s. */
    fun awaitWrite(): Preferences = requireNotNull(writes.poll(5, TimeUnit.SECONDS)) {
        "expected a DataStore write, none arrived"
    }

    fun awaitNoWrite(): Preferences? = writes.poll(500, TimeUnit.MILLISECONDS)
}
