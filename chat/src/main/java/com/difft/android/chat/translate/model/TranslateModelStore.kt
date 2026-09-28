package com.difft.android.chat.translate.model

import android.app.DownloadManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.appScope
import com.difft.android.chat.translate.ModelId
import com.difft.android.chat.translate.TranslateLang
import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.chat.translate.backend.TranslateBackendException
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Readiness of one translation model, as rendered by the translate settings screen. */
sealed interface ModelState {
    data object NotStarted : ModelState
    data class Downloading(val bytesSoFar: Long, val totalBytes: Long, val stuck: Boolean) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: FailureReason, val nextRetryAtMs: Long) : ModelState
}

enum class FailureReason { NO_NETWORK, SDK_ERROR, NO_SPACE, DM_DISABLED, STUCK_NO_PROGRESS }

/** Why a download was requested. Decides whether the backoff window is honoured or cleared. */
enum class DownloadTrigger {
    SETTINGS_RETRY,
    SETTINGS_SELECT,
    VPN_UP,
    NETWORK_AVAILABLE,
    FOREGROUND,
    ENTER_CONVERSATION,
    MANUAL_TRANSLATE,
}

/**
 * Owns everything about having the translation models on the device: readiness, download
 * orchestration, progress, backoff and cleanup. Nothing else in the app calls
 * [TranslateBackend.downloadModel] — in particular the translation engine never downloads, it only
 * asks [isReady].
 *
 * App-start cost is zero when no conversation has auto-translate on: [syncOnStart] resolves the
 * required-model set from one SQL query and returns before touching the backend if that set is
 * empty. It is also zero on a build whose backend reports [TranslateBackend.isSupported] false.
 */
@Singleton
class TranslateModelStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:AppStateDataStore private val appState: DataStore<Preferences>,
    private val dbRoomStore: DBRoomStore,
    private val gson: Gson,
    private val backend: TranslateBackend,
) {
    private val probe = TranslateModelDownloadProbe(context)
    private val failureState = TranslateModelFailureState(appState, gson)

    private val _state = MutableStateFlow<Map<ModelId, ModelState>>(emptyMap())

    /** Per-MODEL readiness. The settings screen renders one status row per required model. */
    val state: StateFlow<Map<ModelId, ModelState>> = _state.asStateFlow()

    /**
     * Mirrors the Ready entries of [state]. Exists so [isReady] — which sits on the per-message
     * translation path — is a set lookup with no IO and no suspension.
     */
    @Volatile
    private var readyCache: Set<ModelId> = emptySet()

    /**
     * Models the conversations currently need, as of the last [syncOnStart] / [ensureDownloaded].
     *
     * Additions go through `updateAndGet` because several triggers add concurrently (a conversation
     * opening, the settings screen, the network watcher) and a plain read-modify-write would lose
     * one of them. The two `set` calls are deliberate overwrites, not lost updates: [syncOnStart]
     * recomputes the whole set from the room table and [clearOnLogout] empties it, so both are
     * authoritative and anything added in the meantime is by definition stale.
     */
    private val requiredCache = AtomicReference<Set<ModelId>>(emptySet())

    @Volatile
    private var foreground: Boolean = true

    private val downloadLock = Mutex()

    /** Written only under [downloadLock]; volatile so the watchdog and foreground hooks can read it. */
    @Volatile
    private var inFlight: ModelId? = null

    private var inFlightJob: Job? = null

    @Volatile
    private var watchdogJob: Job? = null

    private val networkWatcher = TranslateNetworkWatcher(context) { isVpn ->
        val trigger = if (isVpn) DownloadTrigger.VPN_UP else DownloadTrigger.NETWORK_AVAILABLE
        requiredCache.get().filterNot { it in readyCache }.forEach { model ->
            appScope.launch { dispatchDownload(model, trigger, ignoreBackoff = false) }
        }
    }

    /**
     * The models a given target needs on the device.
     *
     * Today both targets resolve to {zh}: the backend pivots every pair through English and the
     * English model is always available, so a zh<->en pair needs the Chinese model only. This is the
     * SINGLE place that mapping lives — if a device is ever observed to need the English model too,
     * add it here and every caller follows.
     *
     * ASSUMPTION (not verified against backend internals): `isModelDownloaded(ModelId("en"))` may
     * report false even though en<->zh translates fine. Reconciliation therefore always runs against
     * the set this function returns, never against "every language a setting mentions".
     */
    fun requiredModels(@Suppress("UNUSED_PARAMETER") target: TranslateLang): Set<ModelId> =
        setOf(ModelId(TranslateLang.ZH.code))

    /** Non-suspending gate used on the per-message path. */
    fun isReady(target: TranslateLang): Boolean = requiredModels(target).all { it in readyCache }

    fun isReady(model: ModelId): Boolean = model in readyCache

    /**
     * Reconciles memory, the device and DownloadManager. Idempotent: cold start and the first
     * foreground callback both call it, and the single-in-flight slot plus the backoff window make
     * the second one a no-op.
     */
    fun syncOnStart() {
        if (!backend.isSupported) return
        appScope.launch {
            runCatching { syncInternal() }
                .onFailure { L.w { "[TranslateModel] syncOnStart failed: ${it.stackTraceToString()}" } }
        }
    }

    fun ensureDownloaded(target: TranslateLang, trigger: DownloadTrigger) {
        requiredModels(target).forEach { ensureDownloaded(it, trigger) }
    }

    fun ensureDownloaded(model: ModelId, trigger: DownloadTrigger) {
        requiredCache.updateAndGet { it + model }
        appScope.launch { dispatchDownload(model, trigger, ignoreBackoff = false) }
    }

    /**
     * Settings-screen retry. Clears the backoff and, when the current attempt is wedged, tears the
     * wedged attempt down first — otherwise the button would be a no-op exactly when the user needs
     * it most.
     */
    fun retryNow(target: TranslateLang) {
        requiredCache.updateAndGet { it + requiredModels(target) }
        requiredModels(target).forEach { model ->
            appScope.launch { dispatchDownload(model, DownloadTrigger.SETTINGS_RETRY, ignoreBackoff = true) }
        }
    }

    /** User-initiated cancel from the settings screen. */
    fun cancel(model: ModelId) {
        appScope.launch {
            withContext(NonCancellable) {
                downloadLock.withLock {
                    if (inFlight != model) return@withLock
                    probe.snapshot()?.let { probe.remove(it.downloadId) }
                    inFlightJob?.cancel()
                    inFlight = null
                    inFlightJob = null
                }
            }
            stopWatchdog()
            setState(model, ModelState.NotStarted)
        }
    }

    fun onForeground() {
        if (!backend.isSupported) return
        foreground = true
        syncOnStart()
        inFlight?.let { startWatchdog(it) }
    }

    fun onBackground() {
        foreground = false
        stopWatchdog()
        networkWatcher.unregister()
    }

    /**
     * Active logout. Downloaded models are deliberately kept: they are a device-level resource with
     * no account affinity, and deleting them would cost the next account another 30 MB.
     */
    fun clearOnLogout() {
        L.i { "[TranslateModel] cleared on logout" }
        networkWatcher.unregister()
        stopWatchdog()
        appScope.launch {
            withContext(NonCancellable) {
                downloadLock.withLock {
                    inFlightJob?.cancel()
                    inFlight = null
                    inFlightJob = null
                }
            }
        }
        failureState.clear()
        requiredCache.set(emptySet())
        readyCache = emptySet()
        _state.value = emptyMap()
    }

    private suspend fun syncInternal() {
        failureState.ensureLoaded()
        val required = withContext(Dispatchers.IO) {
            dbRoomStore.getActiveTranslateTargets()
                .mapNotNull { TranslateLang.fromSetting(it) }
                .flatMapTo(mutableSetOf()) { requiredModels(it) }
        }
        requiredCache.set(required)
        if (required.isEmpty()) {
            L.i { "[TranslateModel] syncOnStart required=0 downloaded=0 adopted=0" }
            networkWatcher.unregister()
            return
        }

        var downloaded = 0
        var adopted = 0
        for (model in required) {
            if (isModelOnDevice(model)) {
                downloaded++
                failureState.onSuccess(model)
                setState(model, ModelState.Ready)
                continue
            }
            // A row already in DownloadManager means a previous process left a download running.
            // Re-issuing the download attaches to it (the backend no-ops on "already downloading"),
            // so the call still resolves — but it must not consume a backoff step, hence
            // ignoreBackoff.
            val running = probe.snapshot()?.takeIf { it.status != DownloadManager.STATUS_FAILED }
            if (running != null) adopted++
            dispatchDownload(model, DownloadTrigger.FOREGROUND, ignoreBackoff = running != null)
        }
        L.i { "[TranslateModel] syncOnStart required=${required.size} downloaded=$downloaded adopted=$adopted" }
        updateNetworkWatcher()
    }

    private suspend fun dispatchDownload(
        model: ModelId,
        trigger: DownloadTrigger,
        ignoreBackoff: Boolean,
    ) {
        if (!backend.isSupported) return
        failureState.ensureLoaded()
        downloadLock.withLock {
            if (inFlight != null) {
                val stuckBypass = trigger == DownloadTrigger.SETTINGS_RETRY &&
                    (_state.value[model] as? ModelState.Downloading)?.stuck == true
                if (!stuckBypass) return
                probe.snapshot()?.let { probe.remove(it.downloadId) }
                inFlightJob?.cancel()
                inFlight = null
                inFlightJob = null
            }
            if (isModelOnDevice(model)) {
                failureState.onSuccess(model)
                setState(model, ModelState.Ready)
                return
            }
            if (!probe.isAvailable()) {
                setState(model, ModelState.Failed(FailureReason.DM_DISABLED, 0))
                L.w { "[TranslateModel] download skipped model=${model.code} reason=DM_DISABLED" }
                return
            }
            if (trigger in BACKOFF_CLEARING_TRIGGERS) {
                failureState.reset(model)
            } else if (!ignoreBackoff) {
                val nextRetryAtMs = failureState.nextRetryAtMs(model)
                val remainingMs = nextRetryAtMs - System.currentTimeMillis()
                if (remainingMs > 0) {
                    L.i { "[TranslateModel] backoff skip model=${model.code} trigger=$trigger remainingMs=$remainingMs" }
                    return
                }
            }
            // Admission (check) and claim (assignment) happen inside the same critical section, so
            // two triggers racing cannot both start a download.
            inFlight = model
            inFlightJob = appScope.launch { runDownload(model, trigger) }
        }
    }

    private suspend fun runDownload(model: ModelId, trigger: DownloadTrigger) {
        L.i {
            "[TranslateModel] download start model=${model.code} trigger=$trigger " +
                "failCount=${failureState.failCount(model)}"
        }
        failureState.onAttempt(model)
        try {
            setState(model, ModelState.Downloading(0, 0, stuck = false))
            startWatchdog(model)
            backend.downloadModel(model)
            failureState.onSuccess(model)
            setState(model, ModelState.Ready)
            L.i { "[TranslateModel] download done model=${model.code} ok=true reason=null nextRetryInMs=0" }
        } catch (e: CancellationException) {
            setState(model, ModelState.NotStarted)
            throw e
        } catch (e: Exception) {
            val reason = classifyFailure(e)
            val nextRetryAtMs = failureState.onFailure(model, reason)
            setState(model, ModelState.Failed(reason, nextRetryAtMs))
            L.w {
                "[TranslateModel] download done model=${model.code} ok=false reason=$reason " +
                    "nextRetryInMs=${nextRetryAtMs - System.currentTimeMillis()}"
            }
        } finally {
            stopWatchdog()
            withContext(NonCancellable) {
                downloadLock.withLock {
                    if (inFlight == model) {
                        inFlight = null
                        inFlightJob = null
                    }
                }
            }
        }
        updateNetworkWatcher()
        // Only reachable when this model finished (success or failure, not cancellation). With more
        // than one required model the slot is free now, so pick the next unmet one up immediately
        // instead of waiting for the next external trigger.
        requiredCache.get().firstOrNull { it != model && it !in readyCache }
            ?.let { dispatchDownload(it, trigger, ignoreBackoff = false) }
    }

    /**
     * Polls DownloadManager for byte progress while a download is in flight, and flags it stuck
     * after [STUCK_NO_PROGRESS_MS] without a single new byte (which also covers the
     * PAUSED_WAITING_TO_RETRY / PAUSED_WAITING_FOR_NETWORK cases, since a paused row transfers
     * nothing). A stuck download is NOT cancelled — it may still recover, and the user gets an
     * explicit retry affordance in the settings screen instead.
     */
    private fun startWatchdog(model: ModelId) {
        stopWatchdog()
        watchdogJob = appScope.launch {
            var lastBytes = -1L
            var lastProgressAtMs = System.currentTimeMillis()
            var stuckReported = false
            while (isActive) {
                delay(WATCHDOG_POLL_MS)
                val snapshot = probe.snapshot() ?: continue
                if (snapshot.status == DownloadManager.STATUS_FAILED) {
                    // Drop the dead row so the next snapshot does not adopt it as in-flight. The
                    // backend call fails on its own and drives the state change.
                    probe.remove(snapshot.downloadId)
                    continue
                }
                val now = System.currentTimeMillis()
                if (snapshot.bytesSoFar > lastBytes) {
                    lastBytes = snapshot.bytesSoFar
                    lastProgressAtMs = now
                    stuckReported = false
                }
                val noProgressMs = now - lastProgressAtMs
                val stuck = noProgressMs >= STUCK_NO_PROGRESS_MS
                setState(model, ModelState.Downloading(snapshot.bytesSoFar, snapshot.totalBytes, stuck))
                if (stuck && !stuckReported) {
                    stuckReported = true
                    failureState.onStuck(model)
                    L.w {
                        "[TranslateModel] stuck model=${model.code} " +
                            "bytes=${snapshot.bytesSoFar}/${snapshot.totalBytes} " +
                            "reason=${snapshot.reason} noProgressMs=$noProgressMs"
                    }
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * The watcher only exists to un-stick a download, so it is registered exactly while one is
     * outstanding and the app is in the foreground. A user with auto-translate off never pays for it.
     */
    private fun updateNetworkWatcher() {
        val needed = foreground && requiredCache.get().any { it !in readyCache }
        if (needed) networkWatcher.register() else networkWatcher.unregister()
    }

    private suspend fun isModelOnDevice(model: ModelId): Boolean =
        runCatching { backend.isModelDownloaded(model) }
            .getOrElse {
                L.w { "[TranslateModel] isModelDownloaded failed model=${model.code}: ${it.stackTraceToString()}" }
                false
            }

    private fun setState(model: ModelId, newState: ModelState) {
        val previous = _state.value[model]
        _state.update { it + (model to newState) }
        readyCache = _state.value.filterValues { it is ModelState.Ready }.keys
        if (stateLabel(previous) != stateLabel(newState)) {
            L.i { "[TranslateModel] state model=${model.code} ${stateLabel(previous)} -> ${stateLabel(newState)}" }
        }
    }

    /**
     * Progress ticks arrive every second; logging the raw state would flood the log file, so
     * transitions are keyed on a coarse label that only changes when something meaningful does.
     */
    private fun stateLabel(state: ModelState?): String = when (state) {
        null -> "none"
        ModelState.NotStarted -> "NotStarted"
        ModelState.Ready -> "Ready"
        is ModelState.Downloading -> if (state.stuck) "Downloading(stuck)" else "Downloading"
        is ModelState.Failed -> "Failed(${state.reason})"
    }

    /**
     * Turns the backend's engine-independent reason into the retry policy this store applies. A
     * throwable that is not a [TranslateBackendException] came from somewhere else entirely, so it
     * gets the same treatment as an unclassified engine error.
     */
    private fun classifyFailure(e: Throwable): FailureReason =
        when ((e as? TranslateBackendException)?.kind) {
            TranslateBackendException.Kind.NETWORK -> FailureReason.NO_NETWORK
            TranslateBackendException.Kind.NO_SPACE -> FailureReason.NO_SPACE
            TranslateBackendException.Kind.DOWNLOAD_MANAGER_DISABLED -> FailureReason.DM_DISABLED
            TranslateBackendException.Kind.CANCELLED,
            TranslateBackendException.Kind.UNSUPPORTED,
            TranslateBackendException.Kind.OTHER,
            null -> FailureReason.SDK_ERROR
        }

    private companion object {
        val BACKOFF_CLEARING_TRIGGERS = setOf(
            DownloadTrigger.SETTINGS_RETRY,
            DownloadTrigger.SETTINGS_SELECT,
            DownloadTrigger.VPN_UP,
            DownloadTrigger.MANUAL_TRANSLATE,
        )
        const val WATCHDOG_POLL_MS = 1_000L
        const val STUCK_NO_PROGRESS_MS = 60_000L
    }
}
