package com.difft.android.base.utils.time

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateDataStoreEntryPoint
import com.difft.android.base.storage.AppStateDefaults
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.appScope
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Process-level trusted-time source: a "now" immune to local wall-clock tampering once anchored.
 * [nowMillis] tiers: L1 anchor (serverNow + monotonic elapsedRealtime) → L2 `wall + persistedOffset`
 * clamped to lastKnownServerTime → L3 bare wall clock (fresh install).
 * Invariant: the L1 anchor does not decay, so anchoring once per process is enough.
 * Persistence is fire-and-forget (never blocks [update]; app_state per #725, not Keystore per #894).
 */
object ServerTimeProvider {

    /**
     * @param serverNow server clock at anchor time (ms UTC)
     * @param anchorElapsed [SystemClock.elapsedRealtime] captured at the same instant as serverNow
     */
    data class Anchor(val serverNow: Long, val anchorElapsed: Long)

    /** Persist only when the offset moved by more than this, to avoid disk churn on every API call. */
    private const val OFFSET_PERSIST_THRESHOLD_MS = 1_000L

    /** A single update moving time by more than a day is unusual — surface it for observability. */
    private const val JUMP_WARN_THRESHOLD_MS = 24L * 60 * 60 * 1000

    /**
     * Plausible epoch-ms window (~2020-09 .. 2100). A `serverNow` outside it is unit-confused
     * (e.g. seconds instead of ms — `getServiceUrlV2`'s serverTimestamp is documented as possibly
     * seconds) and would poison the clock, so it is rejected.
     */
    private const val MIN_PLAUSIBLE_MS = 1_600_000_000_000L
    private const val MAX_PLAUSIBLE_MS = 4_102_444_800_000L

    /** A stale/out-of-order source must not rewind an anchored clock; this tolerance passes normal response jitter. */
    private const val BACKWARD_TOLERANCE_MS = 30_000L

    /** Refresh the on-disk lastKnownServerTime at least this often even without an offset move (NTP-stable devices). */
    private const val LAST_KNOWN_PERSIST_INTERVAL_MS = 30L * 60 * 1000

    @Volatile
    private var anchor: Anchor? = null

    /** Cold-start fallback offset (`server - wall`), read from prefs once at process start. */
    @Volatile
    private var persistedOffset: Long = 0L

    /** Highest server time ever seen (in-memory); clamps L2 so wall-clock rollback cannot rewind before it. */
    @Volatile
    private var lastKnownServerTime: Long = 0L

    /** Highest serverNow already written to disk; drives the time-based persist trigger so IO stays sparse. */
    @Volatile
    private var lastPersistedLastKnown: Long = 0L

    // Clock sources indirected so unit tests can inject deterministic fakes via resetForTest.
    @Volatile
    private var wallClock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var elapsedClock: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * Test seam: set false by [resetForTest] so both the async load and persistence become no-ops,
     * keeping unit tests pure-JVM (no DataStore, no [ApplicationHelper] dependency).
     */
    @Volatile
    private var persistenceEnabled: Boolean = true

    /** Cached app_state DataStore; null until first successful [dataStore] resolution. */
    @Volatile
    private var dataStoreCache: DataStore<Preferences>? = null

    /** True once [loadInitial] has successfully seeded from disk, so the cold-start read is not retried. */
    @Volatile
    private var initialLoadDone: Boolean = false

    /** Guards against launching multiple concurrent [loadInitial] coroutines during the resolve window. */
    @Volatile
    private var loadAttemptInFlight: Boolean = false

    /**
     * Resolve the app_state DataStore, caching only on success. Re-resolves on null (not `by lazy`)
     * since [ApplicationHelper] / the Hilt graph may not be ready early in process start.
     */
    private fun dataStore(): DataStore<Preferences>? {
        dataStoreCache?.let { return it }
        return runCatching {
            EntryPointAccessors.fromApplication(
                ApplicationHelper.instance,
                AppStateDataStoreEntryPoint::class.java,
            ).appStateDataStore()
        }.onFailure { L.w { "[ServerTime] dataStore resolve failed: ${it.message}" } }
            .getOrNull()
            ?.also { dataStoreCache = it }
    }

    init {
        // Async cold-start read (see class KDoc for the startup fallback window).
        loadInitial()
    }

    /**
     * Read the persisted cold-start fallback once, async. Only seeds when [update] hasn't already
     * anchored (a fresh anchor beats disk). Fire-and-forget: failures are logged, never propagate.
     */
    private fun loadInitial() {
        if (initialLoadDone || loadAttemptInFlight || !persistenceEnabled) return
        val ds = dataStore() ?: return // not resolvable yet — retried later from update()
        loadAttemptInFlight = true
        appScope.launch(Dispatchers.IO) {
            try {
                if (!persistenceEnabled) return@launch // resetForTest ran before this coroutine executed
                runCatching {
                    val prefs = ds.data.first()
                    if (anchor == null) {
                        persistedOffset = prefs[AppStateKeys.SERVER_TIME_OFFSET] ?: AppStateDefaults.SERVER_TIME_OFFSET
                        val persistedLast = prefs[AppStateKeys.LAST_KNOWN_SERVER_TIME] ?: AppStateDefaults.LAST_KNOWN_SERVER_TIME
                        lastKnownServerTime = maxOf(lastKnownServerTime, persistedLast)
                        lastPersistedLastKnown = maxOf(lastPersistedLastKnown, persistedLast)
                    }
                    initialLoadDone = true
                }.onFailure { L.w { "[ServerTime] initial load failed: ${it.stackTraceToString()}" } }
            } finally {
                loadAttemptInFlight = false
            }
        }
    }

    /** Trusted current time (epoch ms). L1 anchor → L2 clamped persisted offset → L3 bare wall clock. */
    fun nowMillis(): Long {
        anchor?.let { return it.serverNow + (elapsedClock() - it.anchorElapsed) }
        val fallback = wallClock() + persistedOffset
        return maxOf(fallback, lastKnownServerTime)
    }

    /**
     * Rebuild the anchor from a server time representing "now"; throttles persistence, warns on a >24h jump.
     * @param serverNow server clock (ms UTC); `<= 0` is ignored (never clobbers a good anchor).
     * @param source short tag for logs (e.g. "api")
     */
    fun update(serverNow: Long, source: String) {
        if (serverNow <= 0L) return // invalid — never clobber a good anchor

        // Plausibility gate: reject unit-confused values (e.g. seconds instead of ms) that would poison the clock.
        if (serverNow < MIN_PLAUSIBLE_MS || serverNow > MAX_PLAUSIBLE_MS) {
            L.w { "[ServerTime] implausible serverNow rejected source=$source serverNow=$serverNow" }
            return
        }

        loadInitial() // cheap retry of the cold-start read if the DataStore wasn't resolvable at process start

        val wasAnchored = anchor != null
        // Pre-rebuild estimate for jump/staleness detection; first anchor is neither a jump nor stale.
        val previousNow = if (wasAnchored) nowMillis() else serverNow

        // Backward-staleness guard: a stale/out-of-order source must not rewind an anchored clock.
        // Forward jumps stay accepted (server is authoritative) and are surfaced by the >24h warn below.
        if (wasAnchored && serverNow < previousNow - BACKWARD_TOLERANCE_MS) {
            L.w { "[ServerTime] stale serverNow rejected source=$source serverNow=$serverNow currentNow=$previousNow" }
            return
        }

        anchor = Anchor(serverNow, elapsedClock())

        if (serverNow - previousNow > JUMP_WARN_THRESHOLD_MS) {
            L.w { "[ServerTime] large forward jump source=$source deltaMs=${serverNow - previousNow}" }
        }

        // Advance the in-memory rollback clamp on EVERY accepted update, independent of the persist throttle.
        lastKnownServerTime = maxOf(lastKnownServerTime, serverNow)

        val newOffset = serverNow - wallClock()
        // Persist when: first anchor, offset moved beyond the threshold, OR the on-disk lastKnown is stale
        // (>30min) so the rollback clamp stays fresh on NTP-stable devices without per-response IO.
        val offsetMoved = abs(newOffset - persistedOffset) > OFFSET_PERSIST_THRESHOLD_MS
        val lastKnownStale = lastKnownServerTime - lastPersistedLastKnown > LAST_KNOWN_PERSIST_INTERVAL_MS
        if (!wasAnchored || offsetMoved || lastKnownStale) {
            persistedOffset = newOffset
            lastPersistedLastKnown = lastKnownServerTime
            persist()
            L.i { "[ServerTime] anchored source=$source offsetMs=$newOffset" }
        }
    }

    fun isAnchored(): Boolean = anchor != null

    /**
     * Fire-and-forget persistence to app_state. No-op under test or before the DataStore resolves.
     * Reads @Volatile fields INSIDE the edit block (DataStore serializes edits) so the latest write
     * always stores a consistent pair — a captured snapshot could let an older write win the race.
     */
    private fun persist() {
        if (!persistenceEnabled) return
        val ds = dataStore() ?: return
        appScope.launch(Dispatchers.IO) {
            runCatching {
                ds.edit {
                    it[AppStateKeys.SERVER_TIME_OFFSET] = persistedOffset
                    it[AppStateKeys.LAST_KNOWN_SERVER_TIME] = lastKnownServerTime
                }
            }.onFailure { L.w { "[ServerTime] persist failed: ${it.stackTraceToString()}" } }
        }
    }

    /** Reset state + inject deterministic clocks for unit tests; disables persistence (pure-JVM). */
    @VisibleForTesting
    fun resetForTest(
        wallClock: () -> Long,
        elapsedClock: () -> Long,
        persistedOffset: Long = 0L,
        lastKnownServerTime: Long = 0L,
    ) {
        this.persistenceEnabled = false
        this.wallClock = wallClock
        this.elapsedClock = elapsedClock
        this.anchor = null
        this.persistedOffset = persistedOffset
        this.lastKnownServerTime = lastKnownServerTime
        this.lastPersistedLastKnown = lastKnownServerTime
        this.initialLoadDone = true // don't kick off disk reads in unit tests
    }

    @VisibleForTesting
    fun persistedOffsetForTest(): Long = persistedOffset

    @VisibleForTesting
    fun lastKnownServerTimeForTest(): Long = lastKnownServerTime
}
