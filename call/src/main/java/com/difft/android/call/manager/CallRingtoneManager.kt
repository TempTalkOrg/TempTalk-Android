package com.difft.android.call.manager

import android.content.Intent
import android.media.Ringtone
import androidx.annotation.RawRes
import androidx.annotation.VisibleForTesting
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.call.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays, loops and stops call ringtones.
 *
 * Every touch of a [Ringtone] and of the ringtone state runs on [serial], a single-parallelism
 * dispatcher, so dispatched tasks run one at a time and a stop can never interleave with a
 * `play()`. Enqueue order may differ from request order (`issue*` bumps the generation before
 * `submit*` enqueues), which the generation checks resolve: [generation] is bumped on the
 * caller's thread by every request, so a request issued later invalidates queued work
 * immediately. [toneGeneration] is bumped by play/hangup only, so
 * the call-teardown stop that always follows [playHangupRingTone] cannot skip or cut the hangup
 * tone; that tone releases itself after [HANGUP_RELEASE_DELAY_MS].
 *
 * Invariants: [release] is the only caller of `Ringtone.stop()`; [playIfCurrent] is the only
 * caller of `Ringtone.play()`; every request builds a fresh [Ringtone] (a stopped instance would
 * replay through the unowned remote-player path, issue #1313).
 */
@Singleton
class CallRingtoneManager internal constructor(
    private val source: CallRingtoneSource,
    /** Must be single-parallelism and created once per manager. */
    private val serial: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(source: CallRingtoneSource) : this(source, Dispatchers.IO.limitedParallelism(1), appScope)

    /** G: bumped by every request, on the caller's thread. */
    private val generation = AtomicLong(0)

    /** T: highest generation of a play/hangup request; stops never touch it. */
    private val toneGeneration = AtomicLong(0)

    // Confined to [serial].
    private var current: Ringtone? = null
    private var currentIsHangup = false
    private var loopJob: Job? = null

    internal data class ToneSpec(@param:RawRes val resId: Int, val looping: Boolean, val label: String)

    /**
     * Plays the call ringtone for the given call type and role.
     * @param callType 1on1 / group / instant
     * @param roleType caller / callee
     * @param tag log tag of the caller
     */
    fun playRingTone(callType: String?, roleType: String?, tag: String? = null) {
        val gen = issueTone("play", tag, "callType=$callType role=$roleType")
        submitPlay(gen, callType, roleType, tag)
    }

    /** Extracts call type and role from [intent] and plays the ringtone. */
    fun startRingTone(intent: Intent) {
        val callType = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE)
        val roleType = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_ROLE)
        playRingTone(callType, roleType, tag = "showCallNotification")
    }

    /** Plays the hangup tone once. A later [stopRingTone] leaves it alone; it releases itself. */
    fun playHangupRingTone() {
        submitHangup(issueTone("hangup", "hangup", ""))
    }

    /** Stops the current ringtone. Leaves a playing hangup tone alone. */
    fun stopRingTone(tag: String? = null) {
        submitStop(issueStop(tag), tag)
    }

    /**
     * Invalidates every pending play synchronously and enqueues the release on the serial
     * dispatcher. This is not a synchronous stop: `Ringtone.stop()` runs after this returns.
     * A playing hangup tone is left alone and releases itself.
     */
    fun cleanup() {
        stopRingTone(tag = "cleanup")
    }

    // ---- caller-thread half: bump + log. Split from submit* only so tests can reproduce
    // out-of-order enqueue between threads. ----

    @VisibleForTesting
    internal fun issueTone(kind: String, tag: String?, detail: String): Long {
        val gen = generation.incrementAndGet()
        toneGeneration.accumulateAndGet(gen, ::maxOf)
        L.i { "[Call] CallRingtoneManager $kind requested tag=$tag gen=$gen $detail" }
        return gen
    }

    @VisibleForTesting
    internal fun issueStop(tag: String?): Long {
        val gen = generation.incrementAndGet()
        L.i { "[Call] CallRingtoneManager stop requested tag=$tag gen=$gen" }
        return gen
    }

    // ---- serial half ----

    @VisibleForTesting
    internal fun submitPlay(gen: Long, callType: String?, roleType: String?, tag: String?) {
        scope.launch(serial) {
            if (!isLatest(gen)) {
                logSkip("play", gen, tag)
                return@launch
            }
            release(tag, gen)
            val spec = resolveTone(callType, roleType)
            if (spec == null) {
                L.w { "[Call] CallRingtoneManager no tone for callType=$callType role=$roleType" }
                return@launch
            }
            val rt = source.create(spec.resId)
            if (rt == null) {
                L.w { "[Call] CallRingtoneManager getRingtone null tone=${spec.label}" }
                return@launch
            }
            install(rt, isHangup = false)
            L.i { "[Call] CallRingtoneManager start tone=${spec.label} looping=${spec.looping} gen=$gen" }
            if (spec.looping) loop(gen, rt) else playIfCurrent(rt, isLatest(gen))
        }
    }

    @VisibleForTesting
    internal fun submitHangup(gen: Long) {
        scope.launch(serial) {
            // Checks T, not G: a stop never makes a hangup request stale.
            if (!isLatestTone(gen)) {
                logSkip("hangup", gen, "hangup")
                return@launch
            }
            release("hangup", gen)
            val rt = source.create(R.raw.new_call_hangup)
            if (rt == null) {
                L.w { "[Call] CallRingtoneManager getRingtone null tone=hangup" }
                return@launch
            }
            install(rt, isHangup = true)
            L.i { "[Call] CallRingtoneManager start tone=hangup gen=$gen" }
            playIfCurrent(rt, isLatestTone(gen))
            // Yields the serial slot; a later play/hangup may supersede (and release) this tone.
            delay(HANGUP_RELEASE_DELAY_MS)
            if (current === rt) release("hangup-complete", gen)
        }
    }

    @VisibleForTesting
    internal fun submitStop(gen: Long, tag: String?) {
        scope.launch(serial) {
            // A later request releases on its own, so a stale stop has nothing left to do.
            if (!isLatest(gen)) {
                logSkip("stop", gen, tag)
                return@launch
            }
            if (currentIsHangup) {
                L.i { "[Call] CallRingtoneManager stop leaves hangup tone tag=$tag gen=$gen" }
                return@launch
            }
            release(tag, gen)
        }
    }

    private fun isLatest(gen: Long) = generation.get() == gen

    private fun isLatestTone(gen: Long) = toneGeneration.get() == gen

    private fun logSkip(kind: String, gen: Long, tag: String?) {
        L.i { "[Call] CallRingtoneManager skip stale $kind gen=$gen latest=${generation.get()} tag=$tag" }
    }

    private fun install(rt: Ringtone, isHangup: Boolean) {
        current = rt
        currentIsHangup = isHangup
    }

    /**
     * Sole owner of `Ringtone.stop()`. Idempotent and never suspends. Clears [current] before
     * `stop()` so no later [playIfCurrent] can reach the released instance. `stop()` is called
     * even for a tone that never played so the underlying MediaPlayer is freed instead of being
     * left to a finalizer (finalize-timeout crash, #338). Never `join()`s the loop: it runs on
     * the same single slot and would deadlock; the cancel plus the generation check is enough.
     */
    private fun release(tag: String?, gen: Long) {
        loopJob?.cancel()
        loopJob = null
        val rt = current ?: return
        current = null
        currentIsHangup = false
        try {
            rt.stop()
        } catch (e: Exception) {
            L.w { "[Call] CallRingtoneManager stop threw ${e.javaClass.simpleName}: ${e.message}" }
        }
        L.i { "[Call] CallRingtoneManager stopped tag=$tag gen=$gen" }
    }

    /**
     * Sole owner of `Ringtone.play()`. [live] is computed by the caller right before this call
     * with no suspension point in between. Skips `play()` while the tone is still playing instead
     * of stop + play, which would replay through the remote-player path.
     */
    private fun playIfCurrent(rt: Ringtone, live: Boolean): Boolean {
        if (!live || current !== rt) return false
        return try {
            if (!rt.isPlaying) rt.play()
            true
        } catch (e: Exception) {
            L.w { "[Call] CallRingtoneManager play threw ${e.javaClass.simpleName}: ${e.message}" }
            false
        }
    }

    /**
     * The loop is the play task itself, on [serial]. Cancellation (from [release]) wakes it at a
     * `delay` and propagates; there is deliberately no catch here.
     */
    private suspend fun loop(gen: Long, rt: Ringtone) {
        val self = currentCoroutineContext()[Job]
        loopJob = self
        try {
            while (isLatest(gen) && current === rt) {
                if (!source.isRingerAudible()) {
                    delay(SILENT_RETRY_MS)
                    continue
                }
                if (!playIfCurrent(rt, isLatest(gen))) break
                if (!runCatching { rt.isPlaying }.getOrDefault(false)) {
                    // play() returned without starting (e.g. ring stream at volume 0): back off
                    // instead of re-issuing play() with no suspension point.
                    delay(SILENT_RETRY_MS)
                    continue
                }
                awaitCompletion(gen, rt)
            }
        } finally {
            if (loopJob === self) loopJob = null
            L.i { "[Call] CallRingtoneManager loop ended gen=$gen" }
        }
    }

    private suspend fun awaitCompletion(gen: Long, rt: Ringtone) {
        while (isLatest(gen) && current === rt && runCatching { rt.isPlaying }.getOrDefault(false)) {
            delay(PLAYBACK_POLL_MS)
        }
    }

    private fun resolveTone(callType: String?, roleType: String?): ToneSpec? = when {
        callType == CallType.ONE_ON_ONE.type ->
            if (roleType == CallRole.CALLER.type) {
                ToneSpec(R.raw.new_call_outgoing_1v1, looping = true, label = "outgoing_1v1")
            } else {
                ToneSpec(R.raw.new_call_incomming_1v1, looping = true, label = "incoming_1v1")
            }

        (callType == CallType.GROUP.type || callType == CallType.INSTANT.type) && roleType == CallRole.CALLEE.type ->
            ToneSpec(R.raw.new_call_incomming_group, looping = false, label = "incoming_group")

        else -> null
    }

    internal companion object {
        const val PLAYBACK_POLL_MS = 100L
        const val SILENT_RETRY_MS = 1_000L

        /** Covers the 0.86 s hangup clip plus start latency. */
        const val HANGUP_RELEASE_DELAY_MS = 1_500L
    }
}
