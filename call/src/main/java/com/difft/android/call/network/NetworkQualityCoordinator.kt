package com.difft.android.call.network

import android.os.Looper
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.BuildConfig
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.MediaSendIssueState
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Host and lifecycle owner of [NetworkQualityTracker].
 *
 * The tracker is a lock-free polling state machine, so this class is the single place that drives it
 * and the single place that publishes its snapshot. Everything runs on [Dispatchers.Main.immediate]:
 * the room-event collector that feeds quality events, the two suppression-input collectors, and the
 * 500 ms tick. Public entry points are therefore main-thread-only (guarded by [assertOnMain] in
 * debug builds).
 *
 * Deliberately holds no [Room]: it takes two state flows plus two quality providers, mirroring
 * `CallAudioSetup.bindRoomState`. That keeps the SDK traversal inside the single [create] factory and
 * keeps this class testable without mocking the value-class participant maps.
 *
 * This is also the ONLY place in the weak-network feature that logs: the verdict unit and the render
 * layer stay silent (the tracker runs 2x/second and composables recompose at an uncontrolled rate).
 */
internal class NetworkQualityCoordinator(
    private val scope: CoroutineScope,
    private val roomState: StateFlow<Room.State>,
    /**
     * Second suppression input. `CONNECTION_RECOVERING` means the whole meeting link is recovering
     * (SDK resume / reconnect / network loss) even while [roomState] can still read CONNECTED, so
     * judging suppression from the room state alone leaves the hint on screen through an outage the
     * user is already being told about by the "connecting" pill. `SEND_RECOVERING` is deliberately
     * NOT a suppression input: the uplink can degrade while the quality readings stay meaningful,
     * and the verdict must survive that state so the hint can take over the pill the instant the
     * uplink recovers instead of re-earning its delay.
     */
    private val mediaSendIssue: StateFlow<MediaSendIssueState>,
    private val localQualityProvider: () -> ConnectionQuality,
    private val remoteQualityProvider: () -> Map<String, ConnectionQuality>,
    private val callUiController: CallUiController,
    /**
     * MUST stay a monotonic clock. A wall clock jumps backwards on time sync (a hint would stick
     * forever) and forwards on sleep/wake (the hysteresis delays would be skipped). Injectable only
     * so tests can drive virtual time.
     */
    private val nowProvider: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val tracker = NetworkQualityTracker()
    private var stateJob: Job? = null
    private var issueJob: Job? = null
    private var tickJob: Job? = null

    /** Raw halves of the suppression predicate; both are written from the main dispatcher only. */
    private var roomConnected = false
    private var linkRecovering = false

    /**
     * Null until the first suppression evaluation, so a coordinator that starts while the room is
     * ALREADY connected still seeds (the mid-call mount path). Never derive this from
     * [NetworkQualityTracker.onSuppressedChanged]'s return value: the tracker starts unsuppressed, so
     * a healthy-first emission returns false and the seed would be skipped entirely.
     */
    private var lastSuppressed: Boolean? = null

    /** Idempotent. Safe to call before the room connects; both flows' current values are read. */
    fun start() {
        assertOnMain()
        if (stateJob != null) return
        // Prime both halves before either collector runs, so the very first evaluation sees the
        // whole predicate and cannot seed a snapshot that the sibling flow immediately suppresses.
        roomConnected = roomState.value == Room.State.CONNECTED
        linkRecovering = mediaSendIssue.value == MediaSendIssueState.CONNECTION_RECOVERING
        stateJob = scope.launch(Dispatchers.Main.immediate) {
            roomState.collect { state ->
                roomConnected = state == Room.State.CONNECTED
                onSuppressionInput(state, mediaSendIssue.value)
            }
        }
        issueJob = scope.launch(Dispatchers.Main.immediate) {
            mediaSendIssue.collect { issue ->
                linkRecovering = issue == MediaSendIssueState.CONNECTION_RECOVERING
                onSuppressionInput(roomState.value, issue)
            }
        }
    }

    /**
     * Call teardown. Cancels every job, wipes the tracker (the Room object can be reused across two
     * consecutive calls) and publishes an empty snapshot so a surviving UI (PiP) stops rendering.
     */
    fun stop() {
        assertOnMain()
        stateJob?.cancel()
        stateJob = null
        issueJob?.cancel()
        issueJob = null
        stopTicking()
        lastSuppressed = null
        roomConnected = false
        linkRecovering = false
        tracker.reset()
        publish()
    }

    /**
     * One SDK quality reading.
     *
     * [identity] is ignored for the local participant — the local entry is always keyed by
     * [LOCAL_KEY] so it can never split into two `isLocal` entries (identity is null before join, and
     * the bare uid the UI uses differs in shape from the SDK's "<uid>.<deviceId>"). A remote reading
     * with no identity is dropped: it could neither be keyed nor rendered.
     */
    fun onQualityChanged(identity: String?, quality: ConnectionQuality, isLocal: Boolean) {
        assertOnMain()
        val key = if (isLocal) LOCAL_KEY else identity ?: return
        val level = quality.toNetworkQualityLevel()
        val now = nowProvider()
        if (tracker.onQualityChanged(key, level, isLocal, now)) {
            L.i { "[Call] NetworkQuality raw ${if (isLocal) "local" else identity} level=$level" }
        }
        if (tracker.evaluate(now)) publishWithLog()
    }

    /** Participant left: drop the entry and publish immediately, without waiting for the next tick. */
    fun onParticipantLeft(identity: String?) {
        assertOnMain()
        tracker.onParticipantLeft(identity ?: return)
        publish()
    }

    /**
     * Union of the two suppression inputs: nothing is shown unless the room is connected AND the
     * whole meeting link is not recovering. Only the transitions matter — a repeated emission of
     * either flow that leaves the union unchanged must not restart any timer.
     */
    private fun onSuppressionInput(state: Room.State, issue: MediaSendIssueState) {
        val suppressed = !roomConnected || linkRecovering
        if (suppressed == lastSuppressed) return
        lastSuppressed = suppressed
        val now = nowProvider()
        tracker.onSuppressedChanged(isSuppressed = suppressed, now = now)
        L.i { "[Call] NetworkQuality suppressed=$suppressed roomState=$state issue=$issue" }
        if (!suppressed) {
            reseed(now)
            startTicking()
        } else {
            // Nothing renders while suppressed AND leaving suppression restarts every timer at
            // `now`, so any hysteresis advanced here would be discarded — stopping the tick is
            // semantically neutral, not just an energy saving.
            stopTicking()
        }
        publish()
    }

    /**
     * Re-seed after (re)connect, and the mid-call mount path — deliberately the same code.
     *
     * Ordering: [NetworkQualityTracker.onSuppressedChanged] must already have dropped every published
     * verdict and restarted every `since` at [now] before this runs, so that nothing seeded here can
     * republish a pre-outage Poor onto a healthy link. Today that clear is a blanket sweep over all
     * entries, which makes the reverse order behave the same — but the sweep can only reach entries
     * that already exist, so seeding first would make correctness depend on it staying blanket.
     */
    private fun reseed(now: Long) {
        tracker.onQualityChanged(
            identity = LOCAL_KEY,
            level = localQualityProvider().toNetworkQualityLevel(),
            isLocal = true,
            now = now,
        )
        // ONE read: its keys are also the authoritative present-member set handed to retainRemotes,
        // so seeding and pruning cannot disagree about who is in the room.
        val remotes = remoteQualityProvider()
        remotes.forEach { (identity, quality) ->
            tracker.onQualityChanged(identity, quality.toNetworkQualityLevel(), isLocal = false, now)
        }
        // AFTER seeding: a full SDK disconnect can clear the member table without emitting
        // per-participant leave events, and the Room object may be reused across calls.
        tracker.retainRemotes(remotes.keys)
        L.i { "[Call] NetworkQuality reseed remotes=${remotes.size}" }
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                delay(TICK_INTERVAL_MS)
                // Allocation-free steady state: view() is only built when a tier actually changed.
                if (tracker.evaluate(nowProvider())) publishWithLog()
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun publish() = callUiController.setNetworkQuality(tracker.view())

    /**
     * Publish + one transition log. Kept separate from [publish] on purpose: the log is gated on an
     * actual tier switch, so merging the two would emit a line on every one of the 7200 ticks an hour.
     */
    private fun publishWithLog() {
        val view = tracker.view()
        callUiController.setNetworkQuality(view)
        L.i { "[Call] NetworkQuality published local=${view.local} badRemotes=${view.badRemoteIdentities.size}" }
    }

    private fun assertOnMain() {
        if (!BuildConfig.DEBUG) return
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "[Call] NetworkQualityCoordinator must be driven from the main dispatcher (tracker is lock-free)"
        }
    }

    companion object {
        /** Spec-mandated poll interval; the tracker owns no timer of its own. */
        const val TICK_INTERVAL_MS = 500L

        /**
         * Opaque tracker key for the local entry. Cannot collide with a remote key: those are always
         * `Participant.identity.value` ("<uid>.<deviceId>").
         */
        private const val LOCAL_KEY = "__local__"

        /** The only place that touches the LiveKit [Room] object. */
        fun create(
            scope: CoroutineScope,
            room: Room,
            callUiController: CallUiController,
            mediaSendIssue: StateFlow<MediaSendIssueState>,
        ) =
            NetworkQualityCoordinator(
                scope = scope,
                roomState = room::state.flow,
                // Reuses the already-merged send state instead of collecting the SDK flow again, so
                // the pill and the suppression predicate can never disagree about link recovery.
                mediaSendIssue = mediaSendIssue,
                localQualityProvider = { room.localParticipant.connectionQuality },
                remoteQualityProvider = {
                    room.remoteParticipants.values
                        .mapNotNull { participant ->
                            participant.identity?.value?.let { it to participant.connectionQuality }
                        }
                        .toMap()
                },
                callUiController = callUiController,
            )
    }
}
