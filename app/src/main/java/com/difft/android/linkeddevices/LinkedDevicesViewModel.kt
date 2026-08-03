package com.difft.android.linkeddevices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.websocket.api.messages.multidevice.DeviceInfo
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Linked Devices list screen (MVI). Holds no Android Context.
 *
 * Pushes the count into [LinkedDevicesCountStore] on every successful load so the Settings badge
 * reflects an unlink instantly. 401/403 → [LogoutManager.doLogoutWithoutRemoveData]; the passive
 * badge store deliberately never logs out.
 */
@HiltViewModel
class LinkedDevicesViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val logoutManager: LogoutManager,
    private val countStore: LinkedDevicesCountStore,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val hasLoadedOnce: Boolean = false, // only the first load spins full-screen; later loads never do
        val devices: List<DeviceUiState> = emptyList(),
    )

    sealed interface UiEvent {
        data object UnlinkFailed : UiEvent

        // List fetch failed (non-cancellation, non-logout). One-shot toast; the list/entry stay put.
        data object FetchFailed : UiEvent
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    // Expect-more-devices poll: after the user starts linking from this screen, poll until a new
    // device appears so a completed link reflects without a manual refresh.
    private var expectingMoreDevices = false
    private var baselineDeviceCount = 0
    private var pollTickCount = 0
    private var pollJob: Job? = null

    /**
     * Driven by each host's onResume. Dedupes concurrent refreshes; `force=true` (unlink/pull/poll)
     * cancels an in-flight refresh so an unlink's re-fetch can never be swallowed and a stale
     * pre-DELETE GET can never revive a removed row. `silent=true` (poll) suppresses the failure
     * toast. Full-screen spinner shows only before the first successful load.
     */
    fun refresh(force: Boolean = false, silent: Boolean = false): Job? {
        val previous = loadJob
        if (previous?.isActive == true && !force) return previous
        // Publish the successor BEFORE cancelling the old job: waiters resumed by that cancellation
        // (unlink's settle loop) must already observe the new loadJob, or they would release their
        // guard against a job that never fetched.
        val next = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _uiState.update { it.copy(isLoading = !it.hasLoadedOnce) }
            try {
                val devices = deviceRepository.getDevices().map { it.toUiState() }
                countStore.update(devices.size) // keep the Settings badge fresh
                L.i { "[LinkedDevices] refresh: loaded count=${devices.size}" }
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, hasLoadedOnce = true, devices = devices)
                }
                onDevicesLoaded(devices.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthorizationFailedException) {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false, hasLoadedOnce = true) }
                clearExpecting() // session is gone; the silent poll must not keep firing
                logoutOnAuthFailure("refresh", e.code)
            } catch (e: Exception) {
                // Keep any list already shown; surface a one-shot toast unless this is a silent poll.
                // A failed attempt still counts as the first load, so retries never respin the
                // full-screen spinner over the Link New Device entry.
                L.w { "[LinkedDevices] refresh: failed silent=$silent: ${e.stackTraceToString()}" }
                _uiState.update { it.copy(isLoading = false, isRefreshing = false, hasLoadedOnce = true) }
                if (!silent) _events.trySend(UiEvent.FetchFailed)
                // Arm the poll even when the first post-scan fetch fails, so linking still resolves.
                if (expectingMoreDevices) startExpectPollIfNeeded()
            }
        }
        loadJob = next
        previous?.cancel()
        next.start()
        return next
    }

    /** Pull-to-refresh: forced fetch with the pull indicator shown until done. */
    fun pullRefresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        refresh(force = true)
    }

    /**
     * Tapping "Link New Device": records the current count as the baseline and starts a fresh poll
     * session (re-entering the scan flow re-bases and re-counts). The poll stops once the count
     * exceeds the baseline.
     */
    fun onLinkNewDeviceClicked() {
        baselineDeviceCount = _uiState.value.devices.size
        expectingMoreDevices = true
        pollTickCount = 0
        L.i { "[LinkedDevices] expect-more: armed baseline=$baselineDeviceCount" }
    }

    /** Host onPause/onStop: stop the poll timer but keep the expectation (resume re-arms it). */
    fun stopExpecting() {
        pollJob?.cancel()
        pollJob = null
    }

    /** After a successful load: satisfy, re-base, or (re)arm the expect-more poll. */
    private fun onDevicesLoaded(count: Int) {
        if (!expectingMoreDevices) return
        if (count > baselineDeviceCount) {
            L.i { "[LinkedDevices] expect-more: satisfied count=$count baseline=$baselineDeviceCount" }
            clearExpecting()
        } else {
            // A device was unlinked mid-session: re-base so a later +1 still satisfies.
            if (count < baselineDeviceCount) baselineDeviceCount = count
            startExpectPollIfNeeded()
        }
    }

    // Silent poll, capped at MAX_EXPECT_POLL_TICKS so abandoning the scan flow can't poll forever.
    private fun startExpectPollIfNeeded() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (expectingMoreDevices) {
                delay(EXPECT_POLL_INTERVAL_MS)
                if (!expectingMoreDevices) break
                if (pollTickCount >= MAX_EXPECT_POLL_TICKS) {
                    L.i { "[LinkedDevices] expect-more: tick limit reached, giving up" }
                    clearExpecting()
                    break
                }
                pollTickCount++
                refresh(force = true, silent = true)
            }
        }
    }

    private fun clearExpecting() {
        expectingMoreDevices = false
        pollJob?.cancel()
        pollJob = null
    }

    /** Unlink then server-authoritative re-fetch. Never optimistically drops the row. */
    fun unlink(deviceId: Int) {
        if (_uiState.value.devices.firstOrNull { it.id == deviceId }?.isUnlinking == true) return
        setUnlinking(deviceId, true)
        viewModelScope.launch {
            try {
                deviceRepository.removeDevice(deviceId)
                L.i { "[LinkedDevices] unlink: success id=$deviceId" }
                // Re-fetch is the source of truth. Keep isUnlinking until it settles so a repeat
                // tap cannot fire a duplicate DELETE, then clear unconditionally: a successful
                // re-fetch replaces the list; a failed one keeps the old list and without the
                // clear the row would stay stuck "unlinking". A concurrent force refresh (poll
                // tick) may cancel-and-replace the job we started, and join() returns normally on
                // cancellation — keep joining until the latest attempt settles.
                refresh(force = true)
                while (true) {
                    val attempt = loadJob ?: break
                    attempt.join()
                    if (loadJob === attempt) break
                }
                setUnlinking(deviceId, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthorizationFailedException) {
                setUnlinking(deviceId, false)
                logoutOnAuthFailure("unlink id=$deviceId", e.code)
            } catch (e: Exception) {
                L.w { "[LinkedDevices] unlink: failed id=$deviceId: ${e.stackTraceToString()}" }
                setUnlinking(deviceId, false) // list unchanged, row retryable
                _events.trySend(UiEvent.UnlinkFailed)
            }
        }
    }

    // Single point for the 401/403 logout policy shared by refresh() and unlink().
    private fun logoutOnAuthFailure(scene: String, code: Int) {
        L.i { "[LinkedDevices] $scene: auth failed code=$code, logging out" }
        logoutManager.doLogoutWithoutRemoveData()
    }

    private fun setUnlinking(deviceId: Int, value: Boolean) =
        _uiState.update { s ->
            s.copy(devices = s.devices.map { if (it.id == deviceId) it.copy(isUnlinking = value) else it })
        }

    // When lastSeen precedes created, show created.
    private fun DeviceInfo.toUiState() = DeviceUiState(
        id = id,
        rawName = name,
        created = created,
        lastActive = if (lastSeen <= created) created else lastSeen,
    )

    private companion object {
        const val EXPECT_POLL_INTERVAL_MS = 10_000L // 10s poll cadence
        const val MAX_EXPECT_POLL_TICKS = 18        // 18 × 10s = 3 min cap per scan session
    }
}
