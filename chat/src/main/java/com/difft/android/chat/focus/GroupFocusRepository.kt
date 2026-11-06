package com.difft.android.chat.focus

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.di.AppCoroutineScope
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Account-level group focus cache. The server owns the state; this class only stores it and applies
 * it by version:
 *
 * - a snapshot replaces the cached focus only when its `version` is greater (notify type 29, a GET
 *   and a write response all go through the same gate, so a local echo is a no-op);
 * - `featureEnabled` is taken only from a GET (the coalesced fetch), never from a push or a write
 *   response, with one exception that only turns it off: any response with business status 32 clears
 *   it at once and bumps the switch epoch, and a GET that started before the bump (an older answer)
 *   applies its `focus` by version but not its `featureEnabled`;
 * - consumers read [effective] (or [effectiveNow] on a decision path), which judges the snapshot
 *   against the trusted clock; a timer re-publishes it at `until`, so expiry needs no new version.
 */
@Singleton
class GroupFocusRepository internal constructor(
    apiProvider: () -> FocusApi,
    private val store: GroupFocusStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val selfId: () -> String? = { null },
) {
    @Inject
    constructor(
        @ChativeHttpClientModule.Chat httpClient: dagger.Lazy<ChativeHttpClient>,
        store: GroupFocusStore,
        @AppCoroutineScope scope: CoroutineScope,
    ) : this(
        { httpClient.get().getService(FocusApi::class.java) },
        store,
        scope,
        ServerTimeProvider::nowMillis,
        { globalServices.myId.takeIf { it.isNotEmpty() } },
    )

    private val api by lazy(apiProvider)
    private val mutex = Mutex()
    private val fetchMutex = Mutex()
    private var loaded = false
    private var expiryJob: Job? = null

    /** Bumped on every status 32; a GET applies its `featureEnabled` only if it started in the current epoch. */
    private val switchEpoch = AtomicInteger()

    private val _state = MutableStateFlow(GroupFocusState())

    /** The cached feature switch and server snapshot. Use [effective] for decisions and UI. */
    val state: StateFlow<GroupFocusState> = _state.asStateFlow()

    private val _effective = MutableStateFlow(EffectiveFocus.INACTIVE)

    /** The cached state judged against the trusted clock; re-published when `until` passes. */
    val effective: StateFlow<EffectiveFocus> = _effective.asStateFlow()

    val featureEnabled: Boolean get() = _state.value.featureEnabled

    private val fetchCoalescer = FocusFetchCoalescer(scope) { fetch() }

    init {
        scope.launch { ensureLoaded() }
    }

    /** Focus judged right now; for synchronous decision paths, where [effective] may lag a timer tick. */
    fun effectiveNow(): EffectiveFocus = EffectiveFocus.derive(_state.value, clock())

    /** Loads the persisted state once. Idempotent; every mutation also calls it first. */
    suspend fun ensureLoaded() {
        mutex.withLock {
            loadLocked()
            reconcileOwnerLocked()
        }
    }

    /**
     * Applies a snapshot from a FOCUS notify (type 29) or any other push. Only the version gate
     * applies; `featureEnabled` is untouched.
     */
    suspend fun apply(snapshot: FocusSnapshot, trigger: String?) {
        mutex.withLock {
            loadLocked()
            applyLocked(snapshot, featureEnabled = null, source = "notify:${trigger ?: "-"}")
        }
    }

    /**
     * Re-derives [effective] from the cached state against the trusted clock and re-arms the expiry
     * timer, without a request. Call it before a foreground GET: a timer that slept through deep idle may
     * have missed `until`, and a failed GET would otherwise leave the UI stale.
     */
    suspend fun recomputeLocally() {
        mutex.withLock {
            loadLocked()
            reconcileOwnerLocked()
            republishLocked()
        }
    }

    /**
     * Call after the local user's leave or disband request returned [response]: on success the server
     * state may have changed (the focus group is gone), so reconcile at once instead of waiting for the echo.
     */
    fun onLocalGroupExit(response: BaseResponse<*>) {
        if (response.isSuccess()) requestRefresh("leave")
    }

    /** Requests a GET. Coalesced: one in flight plus one trailing run; no request is dropped. */
    fun requestRefresh(reason: String) {
        L.i { "[GroupFocus] refresh requested reason=$reason" }
        fetchCoalescer.request()
    }

    /** Opens focus on [gid], or reselects after an invalid state. [durationSeconds] is 3600, 28800 or null (manual end). */
    suspend fun start(gid: String, durationSeconds: Int?): FocusResult =
        write("start") { api.openFocus(FocusOpenRequest(listOf(gid), durationSeconds)) }

    /** Switches the focused group while focus is on; the server keeps the current end time. */
    suspend fun switchGroup(gid: String): FocusResult =
        write("switch") { api.openFocus(FocusOpenRequest(listOf(gid), null)) }

    /** Changes the end time of a running focus; null duration = manual end. */
    suspend fun changeUntil(durationSeconds: Int?): FocusResult =
        write("until") { api.changeUntil(FocusUntilRequest(durationSeconds)) }

    /** Patches the "other groups" options; null leaves a field unchanged. At least one must be given. */
    suspend fun updateOptions(
        othersAllowMentionMe: Boolean? = null,
        othersAllowMentionAll: Boolean? = null,
        othersAllowGroupCall: Boolean? = null,
        foldOthers: Boolean? = null,
    ): FocusResult {
        if (listOf(othersAllowMentionMe, othersAllowMentionAll, othersAllowGroupCall, foldOthers).all { it == null }) {
            return FocusResult.Failure(FocusError.InvalidParameter)
        }
        return write("options") {
            api.updateOptions(FocusOptionsRequest(othersAllowMentionMe, othersAllowMentionAll, othersAllowGroupCall, foldOthers))
        }
    }

    /** Ends focus; the options are kept by the server. */
    suspend fun end(): FocusResult = write("end") { api.endFocus() }

    private suspend fun write(op: String, call: suspend () -> BaseResponse<FocusResponse>): FocusResult {
        val response = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[GroupFocus] write failed op=$op error=${e.javaClass.simpleName} ${e.message}" }
            return FocusResult.Failure(FocusError.Network(e.message))
        }
        val focus = response.data?.focus
        if (response.isSuccess() && focus != null) {
            mutex.withLock {
                loadLocked()
                // The write response's featureEnabled is not trusted: only a GET sets it.
                applyLocked(focus, featureEnabled = null, source = "write:$op")
            }
            return FocusResult.Success(focus)
        }
        val error = if (response.isSuccess()) FocusError.Network("empty response") else FocusError.fromStatus(response.status)
        L.w { "[GroupFocus] write rejected op=$op status=${response.status}" }
        if (error is FocusError.FeatureDisabled) {
            onFeatureDisabled("write-$op")
        } else if (error is FocusError.Conflict) {
            requestRefresh("write-$op-status${response.status}")
        }
        return FocusResult.Failure(error)
    }

    /**
     * Status 32 from any endpoint: the feature is off, whatever a GET already in flight will say. Clears
     * the switch (persisted, republished so the entries hide) and bumps the epoch before asking again.
     * A GET that itself returned 32 passes `refresh = false`: that answer is the confirmation.
     */
    private suspend fun onFeatureDisabled(source: String, refresh: Boolean = true) {
        mutex.withLock {
            loadLocked()
            switchEpoch.incrementAndGet()
            val current = _state.value
            L.w { "[GroupFocus] feature disabled by status 32 src=$source epoch=${switchEpoch.get()}" }
            if (current.featureEnabled) {
                val next = current.copy(featureEnabled = false)
                _state.value = next
                store.write(next)
            }
            republishLocked()
        }
        if (refresh) requestRefresh("status32-$source")
    }

    /**
     * One GET at a time, request through apply: `featureEnabled` has no version, so overlapping GETs
     * could let an older answer win. The epoch is captured before the request, so an answer that was
     * overtaken by a status 32 still applies its `focus` (by version) but not its `featureEnabled`.
     */
    private suspend fun fetch(): FocusResponse? = fetchMutex.withLock { fetchLocked() }

    private suspend fun fetchLocked(): FocusResponse? {
        val epoch = switchEpoch.get()
        val response = api.getFocus()
        val data = response.data
        if (response.status == FocusError.STATUS_FEATURE_DISABLED) {
            onFeatureDisabled("get", refresh = false)
            return null
        }
        if (!response.isSuccess() || data == null) {
            L.w { "[GroupFocus] get rejected status=${response.status}" }
            return null
        }
        mutex.withLock {
            loadLocked()
            val feature = data.featureEnabled.takeIf { epoch == switchEpoch.get() }
            if (feature == null) L.i { "[GroupFocus] get feature value dropped, switch epoch moved" }
            applyLocked(data.focus, featureEnabled = feature, source = "get")
        }
        return data
    }

    private suspend fun loadLocked() {
        if (loaded) return
        val stored = store.read()
        val owner = selfId()
        // A cache written by another account (passive logout keeps app_state) is not this account's focus.
        _state.value = if (stored.ownerUid != null && owner != null && stored.ownerUid != owner) {
            L.w { "[GroupFocus] discard cache of another account" }
            GroupFocusState()
        } else {
            stored
        }
        loaded = true
        republishLocked()
    }

    /**
     * Drops a cache that belongs to another account once this account is known. [loadLocked] can only
     * judge this when `selfId` is already available; a passive logout keeps app_state, and a load before
     * sign-in sees no owner, so the check repeats on every read and write.
     */
    private suspend fun reconcileOwnerLocked() {
        val owner = selfId() ?: return
        val stored = _state.value.ownerUid ?: return
        if (stored == owner) return
        L.w { "[GroupFocus] discard cache of another account" }
        _state.value = GroupFocusState(ownerUid = owner)
        store.write(_state.value)
        republishLocked()
    }

    private suspend fun applyLocked(snapshot: FocusSnapshot?, featureEnabled: Boolean?, source: String) {
        reconcileOwnerLocked()
        val current = _state.value
        val nextFocus = snapshot?.takeIf { it.version > current.focus.version }?.normalized() ?: current.focus
        val nextFeature = featureEnabled ?: current.featureEnabled
        L.i {
            "[GroupFocus] apply src=$source localV=${current.focus.version} incomingV=${snapshot?.version} " +
                "accepted=${nextFocus !== current.focus} feature=$nextFeature"
        }
        val next = GroupFocusState(nextFeature, nextFocus, ownerUid = selfId() ?: current.ownerUid)
        if (next != current) {
            _state.value = next
            store.write(next)
        }
        // Also when nothing changed: a GET may have corrected the trusted clock, so `effective` and the expiry timer must be re-judged.
        republishLocked()
    }

    /** Publishes [effective] and re-arms the timer for the next `until` crossing. Call under [mutex]. */
    private fun republishLocked() {
        val state = _state.value
        _effective.value = EffectiveFocus.derive(state, clock())
        expiryJob?.cancel()
        expiryJob = null
        val until = state.focus.until
        if (state.featureEnabled && state.focus.enabled && until != null && clock() < until) {
            expiryJob = scope.launch {
                while (true) {
                    val remaining = until - clock()
                    if (remaining <= 0) break
                    delay(remaining)
                }
                _effective.value = EffectiveFocus.derive(_state.value, clock())
                L.i { "[GroupFocus] focus expired locally version=${_state.value.focus.version}" }
                requestRefresh("expiry")
            }
        }
    }

    private fun FocusSnapshot.normalized(): FocusSnapshot = copy(groupIds = groupIds.distinct())
}
