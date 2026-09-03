package com.difft.android.call.media

import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.AudioDeviceManager
import com.difft.android.call.manager.AudioRouteFailure
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.manager.kind
import com.difft.android.call.manager.targetedRoute
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the route contract honest across `AudioSwitch` generations.
 *
 * The library destroys and rebuilds its `AudioSwitch` on `Room.state` transitions and reports
 * nothing when it does, so without this guard every belief derived from the old instance (device
 * list, confirmed route, in-flight attempt, recorded failure) silently survives into the next one —
 * which is how a manual server switch ends up stuck with no check mark and no retry.
 *
 * Four jobs only: translate generation boundaries into contract actions, replay the user's intent
 * once a new generation reports devices, make a switch that never reports observable, and rebuild a
 * switch whose requests the platform keeps ignoring.
 */
internal class AudioRouteLifecycleGuard(
    private val scope: CoroutineScope,
    private val audioDeviceManager: AudioDeviceManager,
    private val roomState: StateFlow<Room.State>,
) {
    private var lifecycleJob: Job? = null
    private var starvationJob: Job? = null
    private val _switchStarved = MutableStateFlow(false)

    @VisibleForTesting
    internal val switchStarved: StateFlow<Boolean> = _switchStarved.asStateFlow()

    /**
     * The previous value this collector actually observed — not the room's current state.
     *
     * `StateFlow` conflates, so a busy main thread can hide the DISCONNECTED value entirely and let
     * only CONNECTING through; that case must still invalidate. Seeing DISCONNECTED first is the
     * exact proof that the boundary was already handled, and it stays true even when a rebuild
     * callback (new device list) beats the CONNECTING dispatch — so a late CONNECTING can never wipe
     * a fresh list out from under the applier.
     *
     * Confined to [collectSwitchGeneration], so no volatile.
     */
    private var lastObservedState: Room.State? = null

    /**
     * Wedge bookkeeping: the kind the current timeout streak belongs to, its length, and the rescue
     * budget already spent.
     *
     * `@Volatile` because [invalidate] and [stop] end an episode from other coroutines while the
     * wedge collector is the only writer that increments. A lost racing update can cost at most one
     * count, and the outcome stays bounded by [MAX_WEDGE_RESCUES] either way.
     */
    @Volatile
    private var timeoutKind: AudioDeviceKind? = null

    @Volatile
    private var consecutiveTimeouts = 0

    @Volatile
    private var rescues = 0

    /**
     * The `Failed` state already counted — compared by REFERENCE, never by value.
     *
     * `AudioRouteSnapshot.copy` carries the same `Failed` instance forward when only the device list
     * changes, while every new attempt allocates a new one. Two attempts on one target produce
     * value-identical `Failed` states, so equality both over-counts list churn (rescuing after a
     * single timeout) and under-counts a genuine second failure whose intervening `Applying` a
     * conflating `StateFlow` dropped.
     */
    @Volatile
    private var lastCountedFailure: AudioRouteState.Failed? = null

    /**
     * The [com.difft.android.call.manager.AudioRouteSnapshot.confirmations] value already
     * processed. Any advance is proof the arbitration answered and must end the streak — including
     * a T4 cross-kind confirm that never appears as a `Confirmed` STATE, and a confirm whose frame
     * a conflating `StateFlow` collapsed into the next failure's (T6 clears `confirmed`, so the
     * counter is the only evidence that survives).
     */
    @Volatile
    private var lastConfirmationsSeen = 0

    /**
     * True between the rescue's `handler.stop()` and its terminal outcome. Fences the starvation
     * watchdog: the rescue deliberately wipes the device list and stops the switch — the
     * watchdog's exact starvation fingerprint — and a rescue parked through a long RECONNECTING
     * would otherwise trigger misleading switchStarved logs plus no-op reseed pulls.
     */
    private val rescueInFlight = MutableStateFlow(false)

    /**
     * Closes the cancel-without-join window at the rescue's tail (see the comment there). Guarded
     * by [rescueStartLock]; raised by [stop], which cleanup always runs BEFORE its own
     * `audioHandler.stop()`.
     */
    private val rescueStartLock = Any()
    private var rescueStartBlocked = false

    fun start() {
        if (lifecycleJob != null) {
            L.w { "[call] audioRoute guard start ignored: already started" }
            return
        }
        synchronized(rescueStartLock) { rescueStartBlocked = false }
        lifecycleJob = scope.launch {
            launch { collectSwitchGeneration() }
            launch { collectSwitchReady() }
            launch { collectStarvation() }
            launch { collectWedgeRescue() }
        }
    }

    fun stop() {
        // Raised FIRST: cancel below is fire-and-forget, and a rescue already past its last
        // suspension point would otherwise race cleanup's audioHandler.stop() with a handler.start()
        // nothing can ever stop again. The latch makes that tail either observe the stop or win the
        // lock before it — and a start that won is still followed by cleanup's handler stop.
        synchronized(rescueStartLock) { rescueStartBlocked = true }
        // Cancelling the parent also cancels the pending starvation watchdog, which is a child of
        // the starvation collector. The flag must be reset too: a stale `true` would misreport the
        // next call if the instance is reused.
        lifecycleJob?.cancel()
        lifecycleJob = null
        starvationJob = null
        _switchStarved.value = false
        // Same reasoning for the wedge state: a reused instance must inherit neither a stale streak
        // nor a spent rescue budget. The budget is per guard lifetime, so this is its only reset.
        endTimeoutStreak()
        lastCountedFailure = null
        lastConfirmationsSeen = 0
        rescues = 0
        rescueInFlight.value = false
    }

    /** DISCONNECTED / CONNECTING are the only two transitions that touch the switch instance. */
    private suspend fun collectSwitchGeneration() {
        roomState.collect { state ->
            when (state) {
                Room.State.DISCONNECTED -> invalidate(state)
                // Reached only from DISCONNECTED (connect() bails out otherwise), so this is always
                // a rebuild. It only needs invalidating when this collector never saw the
                // DISCONNECTED value — i.e. conflation dropped it.
                Room.State.CONNECTING ->
                    if (lastObservedState != Room.State.DISCONNECTED) invalidate(state)
                // No `else` on purpose: a new state added by a library upgrade must break the build
                // rather than silently skip a generation boundary.
                Room.State.CONNECTED, Room.State.RECONNECTING -> Unit
            }
            lastObservedState = state
        }
    }

    private fun invalidate(state: Room.State) {
        // The timeouts belonged to a switch that no longer exists, so the streak cannot carry into
        // the next generation and make it rescue one failure early.
        endTimeoutStreak()
        // Reseed only at CONNECTING: the library publishes DISCONNECTED BEFORE running the
        // AudioSwitchHandler.stop() side effect, so a pull taken on that boundary can race ahead of
        // the teardown and resurrect the DYING generation's device list. At CONNECTING the old
        // switch is already torn down, so a pull returns either nothing or the new generation.
        audioDeviceManager.onAudioSwitchInvalidated(
            reason = "roomState=$state",
            reseed = state == Room.State.CONNECTING,
        )
        // A reseed that lands non-empty leaves no empty→non-empty edge for collectSwitchReady to
        // see, so the requested-route replay must run here or it never runs for this generation.
        if (audioDeviceManager.routeSnapshot.value.availableDevices.isNotEmpty()) onSwitchReady()
    }

    /**
     * A non-empty device list is the only App-observable proof that a live `AudioSwitch` exists and
     * its scanner is running: the list can only be populated by a library callback, and every
     * Android device reports at least a built-in speaker.
     *
     * The empty -> non-empty *edge*, not every emission: the list changes whenever a headset comes
     * and goes, and replaying on each change would double-write the library's own device choice and
     * keep refreshing the applier's budget.
     */
    private suspend fun collectSwitchReady() {
        audioDeviceManager.availableDevices
            .map { it.isNotEmpty() }
            .distinctUntilChanged()
            .filter { it }
            .collect { onSwitchReady() }
    }

    private fun onSwitchReady() {
        val snapshot = audioDeviceManager.routeSnapshot.value
        val count = snapshot.availableDevices.size
        val want = snapshot.requested
        // No explicit user choice yet: the library's own preferred-list pick is the correct route,
        // and rule R6 already moved it into Applying for the applier to verify. Replaying a
        // library-chosen device would write it into `requested` and pollute user intent forever.
        if (want == null) {
            L.i { "[call] audioRoute switchReady count=$count replay=none" }
            return
        }
        // targetedRoute — in-flight target first, observed fact second. NOT settledRoute: aiming at a
        // device already being applied must count as already-targeted, or this replay calls select()
        // again and refreshes the applier's retry budget on every ready edge.
        val current = snapshot.targetedRoute
        if (current?.kind == want.kind) {
            L.i { "[call] audioRoute switchReady count=$count replay=skip reason=alreadyTargeted" }
            return
        }
        L.i {
            "[call] audioRoute switchReady count=$count replay=apply kind=${want.kind} " +
                "libTarget=${current?.kind}"
        }
        // select() is the single entry for a user-intent route and is explicitly NOT subject to the
        // retry loop guard, so it also clears a Failed left over from the previous generation. If
        // the requested device is gone from the new list, select() rejects it with an L.w and the
        // library's own pick stands — which is the correct outcome.
        audioDeviceManager.select(want)
    }

    /**
     * Watchdog for "the library never reported any device".
     *
     * Distinct from the applier's route budget: it fires even when no route attempt exists, which is
     * exactly the case where a stop/start race inside the library would otherwise be invisible — no
     * devices, no panel, no logs, for the whole call.
     */
    private suspend fun collectStarvation() = coroutineScope {
        combine(roomState, audioDeviceManager.availableDevices, rescueInFlight) { state, devices, rescuing ->
            // A rescue wipes the list and stops the switch on purpose; while it is in flight the
            // emptiness is self-inflicted, not starvation, and a reseed pull would be a no-op.
            state != Room.State.DISCONNECTED && devices.isEmpty() && !rescuing
        }.distinctUntilChanged().collect { starved ->
            starvationJob?.cancel()
            if (!starved) {
                _switchStarved.value = false
                return@collect
            }
            starvationJob = launch {
                delay(SWITCH_STARVED_WARN_MS)
                _switchStarved.value = true
                L.w {
                    "[call] audioRoute switchStarved roomState=${roomState.value} " +
                        "waitedMs=$SWITCH_STARVED_WARN_MS reason=libraryReportedNoDevices"
                }
                // Bounded self-heal. A successful pull ends starvation, which makes the outer
                // collector cancel this job at the delay below — so success terminates the loop
                // structurally and the attempt count is only the FAILURE bound. The trailing delay is
                // deliberate: it gives the last attempt a full interval to take effect, which is what
                // makes the verdict below a true "still starved" rather than a race with its own last
                // attempt.
                repeat(STARVED_RESEED_ATTEMPTS) { attempt ->
                    audioDeviceManager.reseedFromLibrary(reason = "starved attempt=${attempt + 1}")
                    delay(STARVED_RESEED_INTERVAL_MS)
                }
                L.w {
                    "[call] audioRoute switchStarvedUnrecovered attempts=$STARVED_RESEED_ATTEMPTS " +
                        "roomState=${roomState.value}"
                }
            }
        }
    }

    /**
     * Self-heal for a wedged platform arbitration.
     *
     * [WEDGE_TIMEOUT_STREAK] consecutive `Failed(TIMEOUT)` on the same kind mean the applier drove
     * correct requests for two full budgets against a target that stayed enumerable the whole time
     * and the platform never moved — the fingerprint of communication-device arbitration that has
     * stopped answering.
     *
     * Collects the raw snapshot: no `distinctUntilChanged` on the state. The two failures of one
     * wedge episode are value-identical, so any equality-based operator in front of this collector
     * can silently swallow the second one — and it would swallow it precisely under the load that
     * produces the wedge. Identity of the `Failed` object is the discriminator instead (see
     * [lastCountedFailure]).
     */
    private suspend fun collectWedgeRescue() {
        audioDeviceManager.routeSnapshot.collect { snapshot ->
            // Checked BEFORE the state: an advanced confirmations counter is proof the arbitration
            // answered even when the confirm itself is not observable as a state — a T4 cross-kind
            // confirm never emits one, and a conflated frame may already carry the failure that
            // cleared `confirmed` (T6). Ending first is correct in either event order: a confirm
            // adjacent to a failure means the platform moved, so this is not a wedge episode.
            if (snapshot.confirmations != lastConfirmationsSeen) {
                lastConfirmationsSeen = snapshot.confirmations
                endTimeoutStreak()
            }
            when (val state = snapshot.state) {
                is AudioRouteState.Failed -> onFailureObserved(state)
                is AudioRouteState.Confirmed -> endTimeoutStreak()
                // Applying is the normal path between the two failures of one episode. Idle is not
                // evidence either way — it says only that nothing is confirmed or in flight — so it
                // must neither count nor end a streak.
                is AudioRouteState.Applying, AudioRouteState.Idle -> Unit
            }
        }
    }

    private suspend fun onFailureObserved(failure: AudioRouteState.Failed) {
        // Reference identity: the same failure re-published because the device list changed under it
        // is one attempt, not two.
        if (failure === lastCountedFailure) return
        lastCountedFailure = failure
        // TIMEOUT only. DEVICE_GONE is the Bluetooth walk-away path and ERROR is a driving fault —
        // neither is "requests ignored while the target stayed enumerable", so neither may rescue.
        if (failure.cause != AudioRouteFailure.TIMEOUT) {
            endTimeoutStreak()
            return
        }
        if (failure.device.kind != timeoutKind) {
            timeoutKind = failure.device.kind
            consecutiveTimeouts = 1
            return
        }
        consecutiveTimeouts++
        if (consecutiveTimeouts >= WEDGE_TIMEOUT_STREAK) rescue(failure.device.kind)
    }

    /**
     * One bounded, deliberately induced generation boundary.
     *
     * Nothing else: the rebuilt switch's first device push re-populates the snapshot,
     * [collectSwitchReady]'s empty -> non-empty edge replays `requested` (user intent survives the
     * wipe by design), and the applier runs a normal verified attempt.
     */
    private suspend fun rescue(kind: AudioDeviceKind) {
        // An evaluated streak is consumed whatever the verdict, so a refused rescue cannot re-fire on
        // every later failure of the same episode.
        endTimeoutStreak()
        val state = roomState.value
        if (state != Room.State.CONNECTED) {
            // RECONNECTING and DISCONNECTED already tear the switch down through the room's own
            // lifecycle, which collectSwitchGeneration translates into a boundary.
            L.w { "[call] audioRoute wedgeRescue skipped kind=$kind roomState=$state reason=notConnected" }
            return
        }
        if (rescues >= MAX_WEDGE_RESCUES) {
            L.w { "[call] audioRoute wedgeRescueExhausted kind=$kind rescues=$rescues" }
            return
        }
        rescues++
        L.w {
            "[call] audioRoute wedgeRescue kind=$kind consecutiveTimeouts=$WEDGE_TIMEOUT_STREAK " +
                "rescues=$rescues/$MAX_WEDGE_RESCUES"
        }
        rescueInFlight.value = true
        try {
            // reseed=false: the switch below is still alive, so a pull would read the DYING generation —
            // the same argument as the DISCONNECTED boundary. Ordered before the rebuild so no belief
            // from the old generation can outlive it.
            audioDeviceManager.onAudioSwitchInvalidated(reason = "wedgeRescue", reseed = false)
            val handler = audioDeviceManager.audioHandler
            handler.stop()
            // stop() posts the old switch's teardown to the OLD handler thread and quitSafely()s it,
            // while start() posts construct + activate to a NEW one — two threads with no ordering
            // barrier. The teardown abandons audio focus and restores the previous audio mode, so landing
            // it after the new activate() would clobber the freshly rebuilt route and turn this rescue
            // into a second, self-inflicted wedge. The dying thread is nulled inside stop() and cannot be
            // joined, so a short suspend is the only barrier available at App level.
            delay(SWITCH_REBUILD_SETTLE_MS)
            // Re-read AFTER the settle, not just before it: cancellation during the delay already
            // throws and skips start(), and the re-read catches a teardown that published
            // DISCONNECTED while the rescue was parked. The residual window — cleanup cancels this
            // scope WITHOUT joining it, and the tail below has no suspension point for that cancel
            // to land on — is closed by rescueStartLock at the start() call itself. Aborting on
            // DISCONNECTED leaves the handler stopped, which is correct: hang-up cleanup stops it
            // anyway.
            //
            // Wait the transient state OUT instead of sampling once: RECONNECTING must never abort. The
            // room calls audioHandler.start() only on the transition INTO CONNECTING, and a successful
            // reconnect resolves RECONNECTING -> CONNECTED directly, so nothing downstream would ever
            // restart the handler this rescue stopped — the call would lose its audio route for good.
            // Suspending here is safe: the only cancellation is call teardown, which skips start().
            val settledState = roomState.first {
                it == Room.State.CONNECTED || it == Room.State.DISCONNECTED
            }
            if (settledState != Room.State.CONNECTED) {
                L.w {
                    "[call] audioRoute wedgeRescue aborted kind=$kind roomState=$settledState " +
                        "reason=teardownDuringSettle"
                }
                return
            }
            // A start that wins this lock is always followed by cleanup's own audioHandler.stop()
            // (cleanup raises the latch via stop() BEFORE stopping the handler); one that loses it
            // never runs. Either way no switch outlives the call.
            synchronized(rescueStartLock) {
                if (rescueStartBlocked) {
                    L.w { "[call] audioRoute wedgeRescue aborted kind=$kind reason=guardStopped" }
                    return
                }
                handler.start()
            }
        } finally {
            rescueInFlight.value = false
        }
    }

    /**
     * Ends the current episode. [lastCountedFailure] deliberately survives: it de-duplicates
     * re-publications of one attempt's failure and is not part of the streak.
     */
    private fun endTimeoutStreak() {
        timeoutKind = null
        consecutiveTimeouts = 0
    }

    private companion object {
        /**
         * Diagnostic threshold, not a routing parameter. Switch creation is one
         * postAtFrontOfQueue and the system delivers already-connected devices to the scanner's
         * callback right after registration — tens of milliseconds in practice, so this leaves
         * ~2 orders of magnitude of headroom before it reports a real defect.
         */
        const val SWITCH_STARVED_WARN_MS = 3_000L

        /**
         * Failure bound only — a successful pull cancels this job structurally (see the loop's
         * comment). Three attempts over [STARVED_RESEED_INTERVAL_MS] cover a switch whose creation
         * is still queued behind a congested AudioSwitchHandlerThread, without turning the watchdog
         * into a poller.
         */
        const val STARVED_RESEED_ATTEMPTS = 3

        /** Two applier poll intervals (2 × 500 ms), so each attempt gets a full observation cycle. */
        const val STARVED_RESEED_INTERVAL_MS = 1_000L

        /**
         * Two full applier verification budgets of correctly driven, ignored requests. A single
         * TIMEOUT can legitimately happen under transient device contention, so one failure never
         * rescues; two is still short enough that the user is in the middle of trying. No timing
         * constant is needed for the pacing — each count costs a whole applier budget by construction.
         */
        const val WEDGE_TIMEOUT_STREAK = 2

        /**
         * Per guard lifetime (= per call), deliberately NOT reset between episodes: a wedge that
         * survives two rebuilds is not client-side recoverable, and further restarts would only churn
         * the user's audio. Past the cap the contract reports today's honest `Failed`.
         */
        const val MAX_WEDGE_RESCUES = 2

        /**
         * Best-effort ordering barrier between the dying switch's teardown and the new switch's
         * `activate()` (see the comment at the `delay` call). Sized for the healthy case — the
         * teardown is a few binder calls into AudioService, milliseconds on an idle thread — but
         * the rescue's own trigger means AudioService may be stalled, and a teardown outliving this
         * budget lands after the new `activate()` and clobbers the rebuilt route. Accepted residue:
         * it is bounded by [MAX_WEDGE_RESCUES], and no app-level deterministic barrier exists (the
         * dying thread is nulled inside `stop()` and unobservable). The real fence is a fork-level
         * change that reuses one handler thread so teardown and construct share a queue.
         */
        const val SWITCH_REBUILD_SETTLE_MS = 150L
    }
}
