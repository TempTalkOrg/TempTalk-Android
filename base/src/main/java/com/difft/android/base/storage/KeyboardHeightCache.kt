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

/**
 * Process-wide cache of the last measured keyboard height (one slot per orientation), mirrored to
 * the `app_state` DataStore so it survives restarts.
 *
 * [get] is called on the main thread from click handlers (the chat action panel sizes itself to the
 * keyboard before the keyboard has been shown this session), so it never waits: it returns whatever
 * is in memory, and 0 when nothing has been loaded or measured yet — callers already fall back to a
 * default panel height for 0. Any synchronous disk read here is unbounded under IO saturation,
 * including a `withTimeoutOrNull` one: DataStore hops onto its own IO-backed scope inside `data`,
 * and that hop is not cancellable from the caller (Crashlytics ANR 4ef85018, variant 81494ec5).
 *
 * The disk value arrives once per process: [StoragePreloader] calls [seed] with the `app_state`
 * snapshot it already reads at startup; [warm] is the fallback load for hosts without that chain,
 * or for a click that lands before the startup seed (both fill via compare-and-set, so order does
 * not matter). Both `app_state` writers (full-screen `InsetAwareConstraintLayout` and the popup
 * chat controller) go through [save], so memory and disk agree for both paths. The legacy
 * `KeyboardAwareLinearLayout` keeps its own SharedPreferences copy on a path no longer reached.
 */
object KeyboardHeightCache {

    /** One orientation's height plus its own retry marker, so a failure in one cannot be absorbed by the other. */
    private class Slot(val key: Preferences.Key<Int>) {
        val px = AtomicInteger(0)

        /** Set when a persist failed, so the next save of the same height retries instead of deduping. */
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

    /** Keyboard height in px for the current orientation, or 0 when unknown. Never blocks. */
    fun get(context: Context): Int {
        val cached = slotFor(context).px.get()
        if (cached == 0) warm(context)
        return cached
    }

    /**
     * Updates memory synchronously and persists asynchronously. Skips the disk write when the slot
     * already holds this height: every new chat screen re-reports the same keyboard, and rewriting
     * `app_state` for it is pure IO. The write stores the slot's value at write time rather than the
     * argument, so writes that land out of order still leave the latest height on disk. After a
     * failed write the next save retries even for an unchanged height.
     */
    fun save(context: Context, heightPx: Int) {
        if (heightPx <= 0) return
        val slot = slotFor(context)
        val unchanged = slot.px.getAndSet(heightPx) == heightPx
        if (unchanged && !slot.writeFailed.compareAndSet(true, false)) return
        val appContext = context.applicationContext
        appScope.launch {
            // Entry-point lookup stays inside runCatching: writers run from insets callbacks where an
            // unavailable graph (tests, teardown) must not break the callback.
            runCatching { dataStore(appContext).edit { it[slot.key] = slot.px.get() } }
                .onFailure {
                    if (it is CancellationException) throw it
                    slot.writeFailed.set(true)
                    L.w { "[KeyboardHeightCache] save failed: ${it.stackTraceToString()}" }
                }
        }
    }

    /** Seeds from an `app_state` snapshot the caller already read; makes [warm] a no-op. */
    fun seed(prefs: Preferences) {
        warmStarted.set(true)
        fill(prefs)
        L.i { "[KeyboardHeightCache] seeded portrait=${portrait.px.get()} landscape=${landscape.px.get()}" }
    }

    /**
     * Loads the persisted heights once per process, off the main thread. A failure is logged once
     * and not retried: the cache then stays empty until the keyboard is measured, which is the same
     * fallback the callers use for a fresh install.
     */
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

    /** Disk values never overwrite a height measured in this process: a live [save] always wins. */
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
