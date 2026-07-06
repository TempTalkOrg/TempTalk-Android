package com.difft.android.chat.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import android.os.Process as AndroidProcess
import com.difft.android.base.log.lumberjack.L
import com.github.TempTalkOrg.audio_pipeline.AudioModule
import com.github.TempTalkOrg.audio_pipeline.OfflineAudioPipeline
import com.github.TempTalkOrg.audio_pipeline.PipelineTapConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue

/**
 * Voice-message recorder: single mic → N parallel AAC m4a candidates via
 * [OfflineAudioPipeline]. Two threads: a `THREAD_PRIORITY_URGENT_AUDIO`
 * mic reader pushes chunks onto an unbounded channel; one IO coroutine
 * drains the channel through denoise + voice-changer + AAC encoding.
 * Decoupling prevents denoise CPU cost from dropping mic samples.
 *
 * AudioRecord buffer ≥ 2.56 s so worst-case processor pauses can't
 * overrun the kernel ring.
 */
class DualCandidateVoiceRecorder(
    private val context: Context,
    private val outputDir: File,
    private val recipes: List<VoiceMessageRecipe> = VoiceMessageRecipes.DEFAULT,
    private val denoiseModel: AudioModule = VoiceMessageRecipes.DEFAULT_DENOISE_MODEL,
    private val callbacks: Callbacks,
    /**
     * Hook the caller injects to delete candidate files (encoder failure
     * cleanup, recorder cancel, abort-too-short / too-large in the outer
     * View). Default is plain `File.delete()`; the production caller
     * routes through `MyBlobProvider.delete()` so any future provider-side
     * bookkeeping (ref counting, quotas, cache index) automatically applies
     * to every file this recorder creates — no path in the recorder
     * bypasses this hook.
     */
    private val fileDeleter: (File) -> Unit = { it.delete() },
) {
    init {
        require(recipes.isNotEmpty()) { "DualCandidateVoiceRecorder requires at least one recipe" }
        // Single-pass dup check that short-circuits on the first collision.
        // HashSet.add() returns false on duplicate, so .all { add(...) } stops
        // at the first dup rather than allocating a full intermediate set.
        val seenIds = HashSet<String>(recipes.size)
        require(recipes.all { seenIds.add(it.id) }) {
            "DualCandidateVoiceRecorder recipe ids must be unique"
        }
    }

    interface Callbacks {
        fun onStarted()
        /**
         * Mic capture has stopped, encoding may still be finalising the m4a
         * files in the background. UIs can show a "Saving..." indicator
         * here if they want — the result still flows through [onStopped].
         */
        fun onStopRequested()
        /**
         * All candidate files are fully written and native resources released.
         * Some entries' `file` may be `null` if that recipe failed to produce
         * output (pipeline init, encoder errors, etc.).
         */
        fun onStopped(candidates: List<VoiceMessageRecordingCandidate>)
        fun onCancelled()
        fun onError(message: String)
    }

    private companion object {
        private const val TAG = "DualCandidateVoiceRecorder"
        private const val SAMPLE_RATE = 48_000
        private const val CHANNEL_COUNT = 1
        private const val AAC_BITRATE = 128_000

        // 480 floats = 10 ms at 48 kHz. Matches RNNoise frame length so the
        // SDK's input ring buffer never needs an awkward partial-frame split.
        private const val FRAME_SIZE = 480

        // ~80 ms per AudioRecord.read — keeps mic loop low-overhead.
        private const val READ_FRAMES_PER_PULL = 8

        // ≥ 2.56 s of AudioRecord internal buffer (FRAME_SIZE * 256 * 4 bytes
        // at PCM_FLOAT). Enough headroom that any realistic processing
        // hiccup on the processor coroutine cannot overrun the kernel ring.
        private const val MIN_AUDIO_RECORD_BUFFER_BYTES = FRAME_SIZE * 256 * 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** Signals the mic reader to exit. Set by [stop]/[cancel], reset by [start]. */
    @Volatile
    private var stopping = false
    private var recordJob: Job? = null

    /** Volatile: written on IO, read by [interruptBlockingRead] on Main. */
    @Volatile
    private var audioRecord: AudioRecord? = null

    /** Volatile: written in processor coroutine, read in [cleanup]. */
    @Volatile
    private var pipeline: OfflineAudioPipeline? = null

    /** recipe id → encoder. Single-threaded access from recordJob coroutine. */
    private val encoders = LinkedHashMap<String, AacM4aEncoder>()

    /** recipe id → output file. Same access discipline as [encoders]. */
    private val outputFiles = LinkedHashMap<String, File>()

    private var startedAtMs: Long = 0L

    /** Peak mic sample (int16 scale) since last [readPeakAmplitude]. Atomic for mic→UI thread safety. */
    private val peakSinceLastRead = AtomicInteger(0)

    val isRunning: Boolean get() = running.get()

    /** Read-and-reset peak amplitude. Same semantics as `MediaRecorder.getMaxAmplitude()`. */
    fun readPeakAmplitude(): Int = peakSinceLastRead.getAndSet(0)

    @SuppressLint("MissingPermission")
    fun start() {
        // Atomic CAS so two concurrent start() calls can never both pass
        // the guard and each spawn a recordJob — the loser would leak its
        // AudioRecord + DFN context (16 MB) because the winner overwrites
        // `recordJob` without joining/cancelling. Plain get()-then-set() is
        // a textbook check-then-act race; the previous code had it.
        if (!running.compareAndSet(false, true)) return
        cancelled.set(false)
        stopping = false
        startedAtMs = System.currentTimeMillis()
        // Reset the result handle BEFORE launching recordJob so any caller
        // that suspends on awaitResult() between here and cleanup() sees
        // the fresh, uncompleted deferred (not the previous recording's
        // already-completed one).
        resultDeferred = CompletableDeferred()

        recordJob = scope.launch {
            var failureMessage: String? = null
            try {
                runRecording()
            } catch (t: Throwable) {
                L.e(t) { "[$TAG] recording failed" }
                failureMessage = t.message ?: "unknown error"
            } finally {
                val candidates = cleanup(deleteFiles = cancelled.get() || failureMessage != null)
                val message = failureMessage
                scope.launch(Dispatchers.Main) {
                    when {
                        message != null -> callbacks.onError(message)
                        cancelled.get() -> callbacks.onCancelled()
                        else -> callbacks.onStopped(candidates)
                    }
                }
                running.set(false)
            }
        }
        scope.launch(Dispatchers.Main) { callbacks.onStarted() }
    }

    /** Request a normal stop; emits [Callbacks.onStopped] when all files are flushed. */
    fun stop() {
        if (!running.get()) return
        L.i { "[$TAG] stop requested" }
        stopping = true
        interruptBlockingRead()
        scope.launch(Dispatchers.Main) { callbacks.onStopRequested() }
    }

    /** Request a cancel; all candidate files are deleted, emits [Callbacks.onCancelled]. */
    fun cancel() {
        if (!running.get()) return
        L.i { "[$TAG] cancel requested" }
        cancelled.set(true)
        stopping = true
        interruptBlockingRead()
    }

    /**
     * Cancel any in-flight recording and wait for cleanup to finish.
     *
     * **Do not call from the Main thread** — cleanup includes
     * `MediaCodec.signalEndOfStream()` finalisation (each encoder bounded
     * by the EOS-drain timeout, see `AacM4aEncoder.drainOutput`), so a
     * worst-case in-call scenario can block the calling thread for several
     * seconds and trip the ANR watchdog. Use [releaseAndJoin] from a
     * coroutine, or wrap this call in `appScope.launch { ... }` /
     * `Dispatchers.IO.run { ... }` from a non-coroutine context (the latter
     * is what [com.difft.android.chat.widget.VoiceRecorderView] already
     * does in `onDetachedFromWindow`).
     *
     * Throws [IllegalStateException] when called from the Main thread, so
     * misuse is caught immediately in development instead of silently
     * regressing into an ANR for users.
     */
    @Suppress("BanRunBlockingOutsideTests")
    fun release() {
        check(Looper.getMainLooper().thread != Thread.currentThread()) {
            "DualCandidateVoiceRecorder.release() must not be called on the Main thread. " +
                "Use releaseAndJoin() from a coroutine, or wrap with appScope.launch { ... }."
        }
        cancel()
        runBlocking { recordJob?.join() }
        scope.cancel()
    }

    /**
     * Suspending counterpart to [release] — safe to call from any coroutine
     * context (including Main-dispatched ones, because the join happens via
     * coroutine suspension instead of blocking the underlying thread).
     */
    suspend fun releaseAndJoin() {
        cancel()
        recordJob?.join()
        scope.cancel()
    }

    /**
     * Suspend until the active recording produces its terminal result.
     * Returns the candidates list on a normal stop, `null` on cancel or
     * error. Safe to call before, during, or after the recording finishes
     * — `CompletableDeferred.await()` on an already-completed deferred
     * returns the cached value immediately without suspending.
     *
     * Callers can drive the recorder through [stop] / [cancel] independently
     * — this just awaits the result.
     */
    suspend fun awaitResult(): List<VoiceMessageRecordingCandidate>? = resultDeferred.await()

    /** One-shot result handle. Reset in [start], completed in [cleanup]. Pre-completed `null` initially. */
    @Volatile
    private var resultDeferred: CompletableDeferred<List<VoiceMessageRecordingCandidate>?> =
        CompletableDeferred<List<VoiceMessageRecordingCandidate>?>().apply { complete(null) }

    // ── Recording coroutine ──────────────────────────────────────────

    @SuppressLint("MissingPermission")
    @Suppress("LongMethod")
    private suspend fun runRecording(): Unit = coroutineScope {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(MIN_AUDIO_RECORD_BUFFER_BYTES)
        L.i {
            "[$TAG] AudioRecord bufferSize=$bufferSize bytes " +
                "(${bufferSize / 4} float samples, ${bufferSize / 4 * 1000 / SAMPLE_RATE} ms)"
        }

        val recorder = AudioRecord(
            // Same source the production VoiceRecorderView uses to keep
            // behaviour stable when the recorder backend is swapped.
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord init failed")
        }
        audioRecord = recorder

        // ── Open one encoder per recipe so chunks have somewhere to land.
        // mkdirs() returns false when the directory already exists or
        // creation fails — distinguish via exists() so we throw a
        // recogniseable error instead of letting the muxer constructor
        // fail later with an opaque IOException.
        if (!outputDir.exists() && !outputDir.mkdirs() && !outputDir.exists()) {
            throw IllegalStateException("Could not create output dir: $outputDir")
        }
        val stamp = System.currentTimeMillis()
        for (recipe in recipes) {
            val file = File(outputDir, "voice-${recipe.id}-$stamp.m4a")
            outputFiles[recipe.id] = file
            val enc = AacM4aEncoder(file, SAMPLE_RATE, CHANNEL_COUNT, AAC_BITRATE, fileDeleter)
            enc.start()
            encoders[recipe.id] = enc
        }

        // ── Start mic capture IMMEDIATELY so DFN cold-start init (100–600 ms
        //    on Pixel-class devices) doesn't eat the front of the recording.
        recorder.startRecording()

        // Unbounded channel between mic IO thread and processor coroutine.
        // 80 ms per chunk, FloatArray(3840) = ~15 KB. Even a 60 s recording
        // with a fully-blocked processor is < 1 MB, so unbounded is safer
        // than risking a single dropped mic sample by capping it.
        val micChannel = Channel<FloatArray>(capacity = Channel.UNLIMITED)

        // ── Processor coroutine: init pipeline, then drain channel, then
        //    flush + signal EOS. One coroutine end-to-end means no
        //    cross-coroutine state to coordinate.
        val processorJob = launch(Dispatchers.IO) {
            var localPipeline: OfflineAudioPipeline? = null
            try {
                val initStart = System.currentTimeMillis()
                L.i { "[$TAG] pipeline init begin model=$denoiseModel recipes=${recipes.map { it.id }}" }
                try {
                    val taps = LinkedHashMap<String, PipelineTapConfig>(recipes.size)
                    for (recipe in recipes) {
                        taps[recipe.id] = recipe.toTapConfig()
                    }
                    localPipeline = OfflineAudioPipeline(
                        context = context,
                        taps = taps,
                        initialModule = denoiseModel,
                    )
                    pipeline = localPipeline
                    L.i { "[$TAG] pipeline ready (init took ${System.currentTimeMillis() - initStart} ms)" }
                } catch (t: Throwable) {
                    // Pipeline failure is non-fatal — all recipes will end up
                    // with file=null (their encoders only ever saw empty
                    // input), and the caller can fall back / surface an error.
                    L.e(t) { "[$TAG] pipeline init failed (all denoise/effect recipes will be empty)" }
                }

                var processedChunks = 0
                for (chunk in micChannel) {
                    val p = localPipeline
                    if (p != null) {
                        val outs = p.processTaps(chunk)
                        for ((id, encoder) in encoders) {
                            outs[id]?.takeIf { it.isNotEmpty() }?.let(encoder::feed)
                        }
                    }
                    processedChunks++
                }
                L.i { "[$TAG] processor drained $processedChunks chunks" }

                // Flush the SDK tail so the last < 10 ms of input is encoded.
                // Skipped on cancel — files are about to be deleted anyway.
                if (!cancelled.get()) {
                    val p = localPipeline
                    if (p != null) {
                        try {
                            val tails = p.flushTaps()
                            for ((id, encoder) in encoders) {
                                tails[id]?.takeIf { it.isNotEmpty() }?.let(encoder::feed)
                            }
                        } catch (t: Throwable) {
                            L.e(t) { "[$TAG] flushTaps failed" }
                        }
                    }
                }
            } catch (t: Throwable) {
                L.e(t) { "[$TAG] processor coroutine failed" }
            }
        }

        // ── Dedicated mic reader thread at audio priority. Pure IO: read +
        //    copy + post to channel. Never touches pipeline or encoders, so
        //    processor CPU cost cannot delay the next AudioRecord.read.
        val readBuf = FloatArray(FRAME_SIZE * READ_FRAMES_PER_PULL)
        val micReaderThread = Thread({
            try {
                AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (t: Throwable) {
                L.w { "[$TAG] set thread priority failed: ${t.message}" }
            }
            try {
                while (!stopping) {
                    val read = try {
                        recorder.read(readBuf, 0, readBuf.size, AudioRecord.READ_BLOCKING)
                    } catch (t: Throwable) {
                        if (stopping) break else throw t
                    }
                    if (read <= 0) {
                        if (stopping) break
                        continue
                    }
                    updatePeakAmplitude(readBuf, read)
                    val chunk = if (read == readBuf.size) readBuf.copyOf() else readBuf.copyOf(read)
                    val sendResult = micChannel.trySend(chunk)
                    if (!sendResult.isSuccess) {
                        L.w {
                            "[$TAG] mic channel send failed (unexpected on UNLIMITED): " +
                                (sendResult.exceptionOrNull()?.message ?: "no exception")
                        }
                    }
                }
            } catch (t: Throwable) {
                L.e(t) { "[$TAG] mic reader thread failed" }
            } finally {
                micChannel.close()
            }
        }, "DualCandidate-MicReader").apply { start() }

        @Suppress("BlockingMethodInNonBlockingContext")
        micReaderThread.join()
        processorJob.join()

        L.i { "[$TAG] recording loop exited, stopping AudioRecord" }
        try {
            recorder.stop()
        } catch (_: Throwable) {
            // Already stopped by interruptBlockingRead — normal path.
        }
    }

    private fun updatePeakAmplitude(buf: FloatArray, count: Int) {
        var localPeak = 0
        for (i in 0 until count) {
            // Convert normalized float to int16-magnitude scale and track
            // running max so the next readPeakAmplitude() returns it.
            val absInt = (buf[i].absoluteValue * 32767f).toInt().coerceAtMost(32767)
            if (absInt > localPeak) localPeak = absInt
        }
        // Atomic max-update — if the UI thread does a getAndSet(0) between
        // our read of the current value and the write, updateAndGet retries
        // until the CAS succeeds, so we never lose a peak to the reset race.
        peakSinceLastRead.updateAndGet { current -> if (localPeak > current) localPeak else current }
    }

    private fun cleanup(deleteFiles: Boolean): List<VoiceMessageRecordingCandidate> {
        L.i { "[$TAG] cleanup start deleteFiles=$deleteFiles" }
        try { audioRecord?.stop() } catch (_: Throwable) {}
        audioRecord?.release()
        audioRecord = null

        for ((_, encoder) in encoders) {
            encoder.signalEndOfStream(deleteFiles)
        }
        encoders.clear()

        pipeline?.release()
        pipeline = null

        val durationMs = System.currentTimeMillis() - startedAtMs
        val candidates = recipes.map { recipe ->
            val file = outputFiles[recipe.id]
            val usable = !deleteFiles && file != null && file.exists() && file.length() > 0
            VoiceMessageRecordingCandidate(
                recipe = recipe,
                file = if (usable) file else null,
                durationMs = durationMs,
            )
        }
        if (deleteFiles) {
            // Defensive: encoders already delete their own file inside
            // signalEndOfStream(deleteFile=true) above, but if the encoder
            // never reached the point of creating the file (very early
            // failure), we still want to make sure we don't leave a stray
            // partial file behind.
            outputFiles.values.forEach(fileDeleter)
        }
        outputFiles.clear()

        L.i {
            "[$TAG] cleanup end candidates=" +
                candidates.joinToString { "${it.recipe.id}(${it.file?.length() ?: 0}B)" }
        }
        // Complete the one-shot result handle. Idempotent on a deferred that
        // already completed (rare: cancel race where cleanup runs twice).
        resultDeferred.complete(if (deleteFiles) null else candidates)
        return candidates
    }

    private fun interruptBlockingRead() {
        // Calling AudioRecord.stop() while a blocking read is in flight makes
        // it return 0 so the mic reader loop exits promptly instead of
        // waiting for its 80 ms slice to fill.
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
            // Recorder not started yet — mic reader will still see
            // stopping=true on its next loop check and exit.
        }
    }
}
