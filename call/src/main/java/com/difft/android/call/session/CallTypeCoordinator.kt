package com.difft.android.call.session

import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.call.CallIntent
import com.difft.android.call.R
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.RoomMetadataPatch
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import io.livekit.android.room.Room
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Owns the meeting type for the lifetime of a call: parses the server's room metadata, runs
 * [CallTypeResolver], and lands the outcome in every place that consumes it — the room
 * controller's `callType` (which the whole UI observes), the shared `CallData` entry, and the
 * instant-call room rename.
 *
 * Supersedes the former `InstantCallConverter`, which only knew the local
 * "1v1 gained a third participant → instant" upgrade and wrote the type in two places that could
 * drift apart. Landing everything here keeps a single writer, which is what makes the type
 * consistent between the UI and the room-event handlers.
 */
internal class CallTypeCoordinator(
    private val scope: CoroutineScope,
    // A nullable provider, not the Room itself: `roomCtl.room` is a fail-loud getter that throws
    // once the call is released, and this class is constructed lazily — first touch can be
    // `LCallViewModel.getCallRoomName()` during a recomposition that races teardown, or after
    // `startRoomDependentWiring` aborted before wiring us up. It returns null once the room is
    // released/not-yet-created (backed by `roomCtl.roomOrNull()`), because the `room.metadata`
    // collector started in [start] outlives release: it lives in `viewModelScope`, which a
    // user-initiated hangup (`doExitClear`) does not cancel, so a metadata emission can arrive
    // after `disconnectAndRelease()` flipped `released`. Every method below drops that late
    // emission on a null room instead of crashing on the fail-loud getter.
    private val roomProvider: () -> Room?,
    private val roomCtl: CallRoomController,
    private val callDataManager: CallDataManager,
    private val contactorCacheManager: ContactorCacheManager,
    private val callIntent: CallIntent,
    private val callRole: CallRole,
    private val mySelfId: String,
    private val json: Json,
    private val roomIdGetter: () -> String?,
) {

    /** Room title shown during the call; gets the instant-call suffix once the call becomes instant. */
    var currentRoomName: String = callIntent.roomName
        private set

    /**
     * Local user's display name, resolved once up front. Pre-fetching it is what lets [land] stay
     * synchronous: the lookup suspends, but the title it feeds has to be in place before the type
     * change is published.
     */
    private var mySelfDisplayName: String? = null

    /**
     * True once [land] applied the instant title, i.e. this call became instant *during* the call.
     * Distinguishes that from joining a room that was already instant, which keeps the name it
     * joined with.
     */
    private var renamedToInstant = false

    /**
     * The coroutines this coordinator launches in [scope] (`viewModelScope`): the display-name
     * prefetch and the `room.metadata` collector. Tracked so [cancelJobs] can stop them at teardown
     * — a user-initiated hangup (`doExitClear`) does NOT cancel `viewModelScope`, so without this the
     * collector would otherwise linger until `onCleared`. The [roomProvider]-null guards already make
     * a lingering collector crash-safe; cancelling it promptly is the belt-and-suspenders cleanup.
     */
    private var prefetchJob: Job? = null
    private var metadataJob: Job? = null

    /**
     * Subscribes to the authoritative room metadata for the whole call.
     *
     * `Room.metadata` is `@FlowObservable` and backed by a `MutableStateFlow`, so this single
     * collector covers both read points the protocol requires: the current value arrives on
     * subscribe (the server sets it while handling `JoinResponse`, in the same function and just
     * ahead of `ttCallResp`), and every later `RoomUpdate` re-emits. Hence there is no per-event
     * refresh hook on `RoomMetadataChanged` / `Connected` / `ParticipantConnected` — and equal
     * values are de-duplicated by the StateFlow, satisfying the "must be a no-op" requirement for
     * an event that fires on every participant join and leave.
     *
     * Blank and absent values are skipped rather than read as "callType cleared": `Room` nulls its
     * metadata on every disconnect, and a manual server-node switch is a disconnect+connect, so the
     * resolved type has to survive that transient null instead of collapsing back to the local guess
     * halfway through the switch.
     */
    fun start() {
        if (callRole == CallRole.CALLER) prefetchJob = scope.launch { prefetchMyDisplayName() }
        val room = roomProvider() ?: return
        metadataJob = scope.launch {
            room::metadata.flow
                .filterNotNull()
                .collect { raw -> if (raw.isNotBlank()) applyMetadata(raw) }
        }
    }

    /**
     * Cancels the coroutines started by [start] at call teardown. Called from `CallCleanupSteps`
     * alongside the other collaborators' `cancelJobs()`, so the metadata collector stops promptly
     * on a user-initiated hangup instead of lingering in `viewModelScope` until `onCleared`.
     * Idempotent and safe to call even if [start] never ran (both jobs stay null).
     */
    fun cancelJobs() {
        prefetchJob?.cancel()
        prefetchJob = null
        metadataJob?.cancel()
        metadataJob = null
    }

    /**
     * Re-runs the decision immediately and synchronously, from the metadata the room currently
     * holds and the live participant count.
     *
     * Needed in addition to [start]'s collector because the join-time microphone default is decided
     * exactly once, inside `RoomEventDispatcher.onConnected`, and has to already see the
     * authoritative type: `1on1` opens the mic while `instant`/`group` must stay muted. Waiting on
     * the collector would race that decision. Cheap and idempotent, so it doubles as the re-run hook
     * for participant-count changes.
     */
    fun resolveNow() {
        val room = roomProvider() ?: return
        val raw = room.metadata?.takeIf { it.isNotBlank() }
        if (raw != null) applyMetadata(raw) else applyCallType(null)
    }

    /**
     * Lands the instant upgrade that an accepted invite has already made certain, instead of waiting
     * for the server to echo it back on `room.metadata`.
     *
     * Not a local guess competing with the server: the server flips `callType` the moment it accepts
     * the invite, so this only closes the gap until the `RoomUpdate` carrying that flip arrives. The
     * gap matters because `CallExitHandler` routes a hangup off the call type — while it still reads
     * `1on1` it takes the END path, which terminates the meeting for everyone including the person
     * just invited, rather than simply leaving it. Normally milliseconds, but unbounded if that
     * RoomUpdate is delayed or lost.
     *
     * Applying it early is safe because instant is a one-way latch in [CallTypeResolver]: the server
     * value that follows confirms this rather than contending with it, and [land] is idempotent.
     */
    fun applyInviteUpgrade() = land(CallType.INSTANT)

    private fun applyMetadata(raw: String) {
        val patch = runCatching { json.decodeFromString<RoomMetadataPatch>(raw) }
            .onFailure { e -> L.e(e) { "[Call] CallTypeCoordinator metadata parse failed" } }
            .getOrNull()
        // Merge rather than replace: an update carrying only some keys must not reset the others to
        // their defaults, which for the publish flags would re-grant a server-imposed restriction.
        if (patch != null) roomCtl.updateRoomMetadata(patch.mergeInto(roomCtl.roomMetadata.value))
        // Fall through even on a failed decode: the participant-count and group corrections predate
        // this field and must keep working, so pass a null server type rather than returning early.
        applyCallType(patch?.callType)
    }

    private fun applyCallType(serverCallType: String?) {
        // Drop a metadata emission that arrives after the room was released: this runs on the
        // teardown-racing collector (see [start]), where the fail-loud room getter would crash.
        val room = roomProvider() ?: return
        val resolved = CallTypeResolver.resolve(
            serverCallType = serverCallType,
            // Read live off the room rather than the participants StateFlow: that flow is populated
            // by a collector started after us, so at onConnected time it can still be empty and
            // would resolve 1on1 for a crowded room — taking the join-time mic default with it.
            participantCount = room.remoteParticipants.size + 1,
            localCallType = callIntent.callType,
            currentCallType = roomCtl.callType.value,
        ) ?: return
        land(resolved)
    }

    private fun land(resolved: CallType) {
        val typeChanged = roomCtl.callType.value != resolved.type
        val becameInstant = typeChanged && resolved == CallType.INSTANT
        // Title first, and synchronously with the type: the UI recomposes off roomCtl.callType, so
        // publishing the type ahead of the rename would draw the instant layout against the
        // pre-instant title until the rename caught up.
        if (becameInstant) {
            renamedToInstant = true
            currentRoomName = instantRoomName()
        }
        if (typeChanged) {
            L.i { "[Call] CallTypeCoordinator callType ${roomCtl.callType.value} -> ${resolved.type}" }
            roomCtl.updateCallType(resolved.type)
        }
        // Deliberately not gated on typeChanged: for an outbound call the CallData entry is created
        // later, once ttCallResp arrives, so a type resolved before that point would never reach it
        // and CallExitHandler would keep reading a stale value. writeBackCallData no-ops on its own.
        writeBackCallData(resolved)
    }

    /**
     * Mirrors the resolved type, and the instant title once there is one, onto the shared `CallData`
     * entry. Not cosmetic: `CallExitHandler` reads `CallData.type` to choose LEAVE vs END semantics on
     * exit, the call list uses it to decide which entries are rejoinable by conversation, and
     * `callName` is the title both the call list and a later rejoin display.
     *
     * The title rides along here, rather than being published once at the moment of the rename,
     * because that one attempt can land while no entry exists yet — an outbound call only creates it
     * once ttCallResp arrives, and [editCallData] silently no-ops until then. Nothing would retry: the
     * rename fires on the single resolve where the type changes, and every later resolve sees
     * `typeChanged == false`. Re-offering the title on every resolve closes that window, and costs
     * nothing when it is already correct because [editCallData] drops writes that change nothing.
     */
    private fun writeBackCallData(resolved: CallType) = editCallData { data ->
        data.copy(
            type = resolved.type,
            // Only once a rename actually happened. Otherwise the entry keeps the name it was
            // created with, which is what a call that was already instant on join should show.
            callName = if (renamedToInstant) currentRoomName else data.callName,
        )
    }

    /**
     * Replaces this call's `CallData` entry with an edited copy, and only if that actually changes
     * something.
     *
     * The copy is what makes the update observable. `CallData` is mutable and `getCallListData()`
     * hands out the map instance the StateFlow currently holds, so editing the entry in place
     * mutates the old value too — the "new" map then compares equal to it and is never emitted,
     * leaving every call-list observer on stale data. Replacing the instance instead is safe because
     * all readers go through `CallDataManager`'s current value rather than caching an entry.
     */
    private fun editCallData(edit: (CallData) -> CallData) {
        val roomId = roomIdGetter() ?: return
        val callList = callDataManager.getCallListData()
        val callData = callList[roomId] ?: return
        val updated = edit(callData)
        if (updated == callData) return
        callDataManager.updateCallingListData(HashMap(callList).apply { put(roomId, updated) })
    }

    private suspend fun prefetchMyDisplayName() {
        mySelfDisplayName = contactorCacheManager.getDisplayNameById(mySelfId)
        // The lookup can land after the rename already happened — a rejoin resolves instant on the
        // first metadata emission — leaving the no-subject default in place. Fill the name in now.
        if (renamedToInstant) {
            currentRoomName = instantRoomName()
            propagateRoomName(currentRoomName)
        }
    }

    /**
     * The instant-call title, without suspending. The caller's own display name needs a suspending
     * lookup, so it comes from the [prefetchMyDisplayName] cache, with the no-subject default
     * ("instant call") covering the window before that lands.
     */
    private fun instantRoomName(): String {
        val suffix = getString(R.string.call_instant_call_title)
        return if (callRole == CallRole.CALLER) {
            mySelfDisplayName?.let { "$it$suffix" } ?: getString(R.string.call_instant_call_title_default)
        } else {
            "${callIntent.roomName}$suffix"
        }
    }

    /**
     * Mirrors the instant title onto the shared `CallData` entry. Best-effort and off the UI path:
     * the in-call title reads [currentRoomName] directly, so it is already correct regardless of
     * whether a `CallData` entry exists yet.
     */
    private fun propagateRoomName(name: String) = editCallData { it.copy(callName = name) }
}
