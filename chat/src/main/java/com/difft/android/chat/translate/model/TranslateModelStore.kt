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
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton


sealed interface ModelState {
    data object NotStarted : ModelState
    data class Downloading(val bytesSoFar: Long, val totalBytes: Long, val stuck: Boolean) : ModelState
    data object Ready : ModelState
    data class Failed(val reason: FailureReason, val nextRetryAtMs: Long) : ModelState
}

enum class FailureReason { NO_NETWORK, SDK_ERROR, NO_SPACE, DM_DISABLED, STUCK_NO_PROGRESS }


enum class DownloadTrigger {
    SETTINGS_RETRY,
    SETTINGS_SELECT,
    VPN_UP,
    NETWORK_AVAILABLE,
    FOREGROUND,
    ENTER_CONVERSATION,
    MANUAL_TRANSLATE,
}











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

    
    val state: StateFlow<Map<ModelId, ModelState>> = _state.asStateFlow()

    



    @Volatile
    private var readyCache: Set<ModelId> = emptySet()

    








    private val requiredCache = AtomicReference<Set<ModelId>>(emptySet())

    @Volatile
    private var foreground: Boolean = true

    private val downloadLock = Mutex()

    
    @Volatile
    private var inFlight: ModelId? = null

    @Volatile
    private var inFlightJob: Job? = null

    @Volatile
    private var watchdogJob: Job? = null

    private val networkWatcher = TranslateNetworkWatcher(context) { isVpn ->
        val trigger = if (isVpn) DownloadTrigger.VPN_UP else DownloadTrigger.NETWORK_AVAILABLE
        requiredCache.get().filterNot { it in readyCache }.forEach { model ->
            appScope.launch { dispatchDownload(model, trigger, ignoreBackoff = false) }
        }
    }

    






    fun requiredModels(@Suppress("UNUSED_PARAMETER") target: TranslateLang): Set<ModelId> =
        setOf(ModelId(TranslateLang.ZH.code))

    
    fun isReady(target: TranslateLang): Boolean = requiredModels(target).all { it in readyCache }

    fun isReady(model: ModelId): Boolean = model in readyCache

    




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

    
    suspend fun awaitReady(target: TranslateLang): Boolean = awaitReady(requiredModels(target))

    








    suspend fun awaitReady(models: Set<ModelId>): Boolean {
        if (!backend.isSupported) return false
        requiredCache.updateAndGet { it + models }
        var gaveUp: DownloadWaitOutcome? = null
        for (model in models) {
            if (isReady(model)) continue
            if (isModelOnDevice(model)) {
                failureState.ensureLoaded()
                failureState.onSuccess(model)
                setState(model, ModelState.Ready)
                continue
            }
            dispatchDownload(model, DownloadTrigger.MANUAL_TRANSLATE, ignoreBackoff = true)
            var waited = DownloadWaitOutcome.COMPLETED
            while (true) {
                val job = downloadLock.withLock { inFlightJob.takeIf { inFlight == model } } ?: break
                waited = awaitDownload(job, model, state)
                
                
                if (waited != DownloadWaitOutcome.COMPLETED || !job.isCancelled) break
            }
            if (waited != DownloadWaitOutcome.COMPLETED) {
                gaveUp = waited
                break
            }
        }
        val ready = models.all { isReady(it) }
        val outcome = if (ready) "ready" else gaveUp?.name?.lowercase() ?: "failed"
        L.i { "[TranslateModel] awaitReady models=${models.joinToString { it.code }} outcome=$outcome" }
        return ready
    }

    
    fun retryNow(target: TranslateLang) {
        requiredCache.updateAndGet { it + requiredModels(target) }
        requiredModels(target).forEach { model ->
            appScope.launch { dispatchDownload(model, DownloadTrigger.SETTINGS_RETRY, ignoreBackoff = true) }
        }
    }

    
    fun cancel(model: ModelId) {
        appScope.launch {
            withContext(NonCancellable) {
                downloadLock.withLock {
                    if (inFlight != model) return@withLock
                    tearDownInFlight()
                }
            }
            stopWatchdog()
            setState(model, ModelState.NotStarted)
        }
    }

    
    private fun tearDownInFlight() {
        probe.snapshot()?.let { probe.remove(it.downloadId) }
        inFlightJob?.cancel()
        inFlight = null
        inFlightJob = null
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
                
                val stuckBypass = trigger in STUCK_RESTART_TRIGGERS &&
                    (_state.value[model] as? ModelState.Downloading)?.stuck == true
                if (!stuckBypass) return
                tearDownInFlight()
                
                
                setState(model, ModelState.NotStarted)
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
        
        
        val me = coroutineContext[Job]
        val owns = { inFlightJob === me }
        var released = false
        try {
            setState(model, ModelState.Downloading(0, 0, stuck = false))
            startWatchdog(model)
            backend.downloadModel(model)
            if (owns()) {
                failureState.onSuccess(model)
                setState(model, ModelState.Ready)
                L.i { "[TranslateModel] download done model=${model.code} ok=true reason=null nextRetryInMs=0" }
            } else {
                
                L.i { "[TranslateModel] replaced attempt ended model=${model.code} ok=true" }
            }
        } catch (e: CancellationException) {
            if (owns()) setState(model, ModelState.NotStarted)
            throw e
        } catch (e: Exception) {
            val reason = classifyFailure(e)
            if (!owns()) {
                
                
                L.i { "[TranslateModel] replaced attempt ended model=${model.code} reason=$reason" }
            } else {
                val nextRetryAtMs = failureState.onFailure(model, reason)
                setState(model, ModelState.Failed(reason, nextRetryAtMs))
                L.w {
                    "[TranslateModel] download done model=${model.code} ok=false reason=$reason " +
                        "nextRetryInMs=${nextRetryAtMs - System.currentTimeMillis()}"
                }
            }
        } finally {
            withContext(NonCancellable) {
                released = downloadLock.withLock {
                    owns().also { if (it) { inFlight = null; inFlightJob = null } }
                }
                if (released) stopWatchdog()
            }
        }
        if (!released) return
        updateNetworkWatcher()
        
        
        
        requiredCache.get().firstOrNull { it != model && it !in readyCache }
            ?.let { dispatchDownload(it, trigger, ignoreBackoff = false) }
    }

    






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

    



    private fun stateLabel(state: ModelState?): String = when (state) {
        null -> "none"
        ModelState.NotStarted -> "NotStarted"
        ModelState.Ready -> "Ready"
        is ModelState.Downloading -> if (state.stuck) "Downloading(stuck)" else "Downloading"
        is ModelState.Failed -> "Failed(${state.reason})"
    }

    




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
        val STUCK_RESTART_TRIGGERS = setOf(DownloadTrigger.SETTINGS_RETRY, DownloadTrigger.MANUAL_TRANSLATE)
        const val WATCHDOG_POLL_MS = 1_000L
        const val STUCK_NO_PROGRESS_MS = 60_000L
    }
}
