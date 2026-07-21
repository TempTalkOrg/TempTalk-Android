package com.difft.android.linkeddevices

import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.signal.DeviceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped best-effort source of truth for the Settings "Linked Devices" count badge.
 * `count == null` means never fetched (badge hidden). Best-effort: swallows all non-cancellation
 * errors, keeps the last-known count, and never logs out.
 *
 * [refresh] always issues a real fetch (lightweight, fired from the Settings tab onResume) but
 * coalesces: a call made while another fetch is in flight returns immediately and is satisfied by
 * that in-flight request.
 *
 * Concurrency: an unlocked [update] push must win over a slow in-flight [refresh]. A monotonic
 * [generation] counter, bumped by every [update], makes a fetch result stale the instant a push
 * lands — [refresh] captures [generation] before its round-trip and commits only if it is unchanged.
 * The capture-vs-commit read is fused with [update]'s bump under [writeLock].
 */
@Singleton
class LinkedDevicesCountStore @Inject constructor(
    private val deviceRepository: DeviceRepository,
) {
    private val _count = MutableStateFlow<Int?>(null)
    val count: StateFlow<Int?> = _count.asStateFlow()

    private val refreshMutex = Mutex()            // coalesces concurrent refresh() into one fetch
    private val writeLock = Any()                 // fuses generation-check + _count write vs update()
    private val generation = AtomicInteger(0)     // bumped by update(); invalidates in-flight fetches

    /** Fetches the latest secondary-device count. Coalesced; best-effort. */
    suspend fun refresh() {
        if (!refreshMutex.tryLock()) {
            L.i { "[LinkedDevices] badge count refresh coalesced; in-flight request will satisfy it" }
            return
        }
        try {
            val genAtStart = generation.get()     // capture before the network round-trip
            val secondaryCount = deviceRepository.getDevices().size // already primary-filtered
            synchronized(writeLock) {
                if (generation.get() != genAtStart) {   // a push landed mid-fetch → result is stale
                    L.i { "[LinkedDevices] badge count fetch discarded (superseded by push)" }
                } else {
                    _count.value = secondaryCount
                    L.i { "[LinkedDevices] badge count refreshed count=$secondaryCount" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w(e) { "[LinkedDevices] badge count refresh failed; keeping last-known" }
        } finally {
            refreshMutex.unlock()
        }
    }

    /** Authoritative latest-wins push from LinkedDevicesViewModel after a successful getDevices(). */
    fun update(count: Int) {
        synchronized(writeLock) {
            generation.incrementAndGet()          // invalidate any in-flight refresh() result
            _count.value = count
        }
        L.i { "[LinkedDevices] badge count pushed count=$count" }
    }
}
