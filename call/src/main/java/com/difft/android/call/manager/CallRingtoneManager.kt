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

















@Singleton
class CallRingtoneManager internal constructor(
    private val source: CallRingtoneSource,
    
    private val serial: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(source: CallRingtoneSource) : this(source, Dispatchers.IO.limitedParallelism(1), appScope)

    
    private val generation = AtomicLong(0)

    
    private val toneGeneration = AtomicLong(0)

    
    private val ringbackStopGeneration = AtomicLong(0)

    
    private var current: Ringtone? = null
    private var currentIsHangup = false
    private var currentIsCallerRingback = false
    private var loopJob: Job? = null

    internal data class ToneSpec(@param:RawRes val resId: Int, val looping: Boolean, val label: String)

    





    fun playRingTone(callType: String?, roleType: String?, tag: String? = null) {
        val gen = issueTone("play", tag, "callType=$callType role=$roleType")
        submitPlay(gen, callType, roleType, tag)
    }

    
    fun startRingTone(intent: Intent) {
        val callType = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE)
        val roleType = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CALL_ROLE)
        playRingTone(callType, roleType, tag = "showCallNotification")
    }

    
    fun playHangupRingTone() {
        submitHangup(issueTone("hangup", "hangup", ""))
    }

    
    fun stopRingTone(tag: String? = null) {
        submitStop(issueStop(tag), tag)
    }

    





    fun stopCallerRingback(tag: String? = null) {
        val covered = ringbackStopGeneration.accumulateAndGet(generation.get(), ::maxOf)
        L.i { "[Call] CallRingtoneManager stop ringback requested tag=$tag covers=$covered" }
        scope.launch(serial) {
            if (!currentIsCallerRingback) {
                L.i { "[Call] CallRingtoneManager no ringback playing tag=$tag" }
                return@launch
            }
            release(tag, generation.get())
        }
    }

    




    fun cleanup() {
        stopRingTone(tag = "cleanup")
    }

    
    

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

    

    @VisibleForTesting
    internal fun submitPlay(gen: Long, callType: String?, roleType: String?, tag: String?) {
        scope.launch(serial) {
            if (!isLatest(gen)) {
                logSkip("play", gen, tag)
                return@launch
            }
            val spec = resolveTone(callType, roleType)
            if (spec?.resId == R.raw.new_call_outgoing_1v1 && gen <= ringbackStopGeneration.get()) {
                L.i { "[Call] CallRingtoneManager skip ringback stopped before it played gen=$gen tag=$tag" }
                return@launch
            }
            release(tag, gen)
            if (spec == null) {
                L.w { "[Call] CallRingtoneManager no tone for callType=$callType role=$roleType" }
                return@launch
            }
            val rt = source.create(spec.resId)
            if (rt == null) {
                L.w { "[Call] CallRingtoneManager getRingtone null tone=${spec.label}" }
                return@launch
            }
            install(rt, isHangup = false, isCallerRingback = spec.resId == R.raw.new_call_outgoing_1v1)
            L.i { "[Call] CallRingtoneManager start tone=${spec.label} looping=${spec.looping} gen=$gen" }
            if (spec.looping) loop(gen, rt) else playIfCurrent(rt, isLatest(gen))
        }
    }

    @VisibleForTesting
    internal fun submitHangup(gen: Long) {
        scope.launch(serial) {
            
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
            
            delay(HANGUP_RELEASE_DELAY_MS)
            if (current === rt) release("hangup-complete", gen)
        }
    }

    @VisibleForTesting
    internal fun submitStop(gen: Long, tag: String?) {
        scope.launch(serial) {
            
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

    private fun install(rt: Ringtone, isHangup: Boolean, isCallerRingback: Boolean = false) {
        current = rt
        currentIsHangup = isHangup
        currentIsCallerRingback = isCallerRingback
    }

    






    private fun release(tag: String?, gen: Long) {
        loopJob?.cancel()
        loopJob = null
        val rt = current ?: return
        current = null
        currentIsHangup = false
        currentIsCallerRingback = false
        try {
            rt.stop()
        } catch (e: Exception) {
            L.w { "[Call] CallRingtoneManager stop threw ${e.javaClass.simpleName}: ${e.message}" }
        }
        L.i { "[Call] CallRingtoneManager stopped tag=$tag gen=$gen" }
    }

    




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

        
        const val HANGUP_RELEASE_DELAY_MS = 1_500L
    }
}
