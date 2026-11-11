package com.difft.android.base.storage

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger












object KeyboardHeightCache {

    
    private class Slot(val key: Preferences.Key<Int>) {
        val px = AtomicInteger(0)

        
        val writeFailed = AtomicBoolean(false)

        fun reset() {
            px.set(0)
            writeFailed.set(false)
        }
    }

    private val portrait = Slot(AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT)
    private val landscape = Slot(AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE)
    private val warmStarted = AtomicBoolean(false)

    @Volatile
    private var warmJob: Job? = null

    
    fun get(context: Context): Int {
        val cached = slotFor(context).px.get()
        if (cached == 0) warm(context)
        return cached
    }

    






    fun save(context: Context, heightPx: Int) {
        if (heightPx <= 0) return
        val slot = slotFor(context)
        val unchanged = slot.px.getAndSet(heightPx) == heightPx
        if (unchanged && !slot.writeFailed.compareAndSet(true, false)) return
        val appContext = context.applicationContext
        appScope.launch {
            
            
            runCatching { dataStore(appContext).edit { it[slot.key] = slot.px.get() } }
                .onFailure {
                    if (it is CancellationException) throw it
                    slot.writeFailed.set(true)
                    L.w { "[KeyboardHeightCache] save failed: ${it.stackTraceToString()}" }
                }
        }
    }

    
    fun seed(prefs: Preferences) {
        warmStarted.set(true)
        fill(prefs)
        L.i { "[KeyboardHeightCache] seeded portrait=${portrait.px.get()} landscape=${landscape.px.get()}" }
    }

    




    fun warm(context: Context) {
        if (!warmStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        warmJob = appScope.launch {
            runCatching { dataStore(appContext).data.first() }
                .onSuccess {
                    fill(it)
                    L.i { "[KeyboardHeightCache] warmed portrait=${portrait.px.get()} landscape=${landscape.px.get()}" }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    L.w { "[KeyboardHeightCache] warm failed: ${it.stackTraceToString()}" }
                }
        }
    }

    @VisibleForTesting
    suspend fun resetForTest() {
        warmJob?.cancelAndJoin()
        warmJob = null
        warmStarted.set(false)
        portrait.reset()
        landscape.reset()
    }

    
    private fun fill(prefs: Preferences) {
        for (slot in listOf(portrait, landscape)) {
            prefs[slot.key]?.takeIf { it > 0 }?.let { slot.px.compareAndSet(0, it) }
        }
    }

    private fun slotFor(context: Context) =
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) landscape else portrait

    private fun dataStore(appContext: Context): DataStore<Preferences> =
        EntryPointAccessors.fromApplication(appContext, AppStateDataStoreEntryPoint::class.java).appStateDataStore()
}
