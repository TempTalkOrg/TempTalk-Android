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

    
    private val switchEpoch = AtomicInteger()

    private val _state = MutableStateFlow(GroupFocusState())

    
    val state: StateFlow<GroupFocusState> = _state.asStateFlow()

    private val _effective = MutableStateFlow(EffectiveFocus.INACTIVE)

    
    val effective: StateFlow<EffectiveFocus> = _effective.asStateFlow()

    val featureEnabled: Boolean get() = _state.value.featureEnabled

    private val fetchCoalescer = FocusFetchCoalescer(scope) { fetch() }

    init {
        scope.launch { ensureLoaded() }
    }

    
    fun effectiveNow(): EffectiveFocus = EffectiveFocus.derive(_state.value, clock())

    
    suspend fun ensureLoaded() {
        mutex.withLock {
            loadLocked()
            reconcileOwnerLocked()
        }
    }

    



    suspend fun apply(snapshot: FocusSnapshot, trigger: String?) {
        mutex.withLock {
            loadLocked()
            applyLocked(snapshot, featureEnabled = null, source = "notify:${trigger ?: "-"}")
        }
    }

    




    suspend fun recomputeLocally() {
        mutex.withLock {
            loadLocked()
            reconcileOwnerLocked()
            republishLocked()
        }
    }

    



    fun onLocalGroupExit(response: BaseResponse<*>) {
        if (response.isSuccess()) requestRefresh("leave")
    }

    
    fun requestRefresh(reason: String) {
        L.i { "[GroupFocus] refresh requested reason=$reason" }
        fetchCoalescer.request()
    }

    
    suspend fun start(gid: String, durationSeconds: Int?): FocusResult =
        write("start") { api.openFocus(FocusOpenRequest(listOf(gid), durationSeconds)) }

    
    suspend fun switchGroup(gid: String): FocusResult =
        write("switch") { api.openFocus(FocusOpenRequest(listOf(gid), null)) }

    
    suspend fun changeUntil(durationSeconds: Int?): FocusResult =
        write("until") { api.changeUntil(FocusUntilRequest(durationSeconds)) }

    
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
        
        _state.value = if (stored.ownerUid != null && owner != null && stored.ownerUid != owner) {
            L.w { "[GroupFocus] discard cache of another account" }
            GroupFocusState()
        } else {
            stored
        }
        loaded = true
        republishLocked()
    }

    




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
        
        republishLocked()
    }

    
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
