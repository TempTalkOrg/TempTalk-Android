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








class RecordingPreferencesDataStore(initial: Preferences? = emptyPreferences()) : DataStore<Preferences> {

    
    
    private val emissions = MutableSharedFlow<Preferences>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val writes = LinkedBlockingQueue<Preferences>()
    private val writeLock = Mutex()

    
    val reads = AtomicInteger(0)

    
    val writeCount = AtomicInteger(0)

    init {
        initial?.let(::emit)
    }

    override val data: Flow<Preferences>
        get() {
            reads.incrementAndGet()
            return emissions
        }

    




    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writeLock.withLock {
            val updated = transform(emissions.first())
            emit(updated)
            writeCount.incrementAndGet()
            writes.put(updated)
            updated
        }

    
    fun emit(prefs: Preferences) = check(emissions.tryEmit(prefs)) { "replay=1 shared flow must accept emit" }

    
    fun awaitWrite(): Preferences = requireNotNull(writes.poll(5, TimeUnit.SECONDS)) {
        "expected a DataStore write, none arrived"
    }

    fun awaitNoWrite(): Preferences? = writes.poll(500, TimeUnit.MILLISECONDS)
}
