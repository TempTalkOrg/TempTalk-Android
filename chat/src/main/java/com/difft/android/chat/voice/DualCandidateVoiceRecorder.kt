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











class DualCandidateVoiceRecorder(
    private val context: Context,
    private val outputDir: File,
    private val recipes: List<VoiceMessageRecipe> = VoiceMessageRecipes.DEFAULT,
    private val denoiseModel: AudioModule = VoiceMessageRecipes.DEFAULT_DENOISE_MODEL,
    private val callbacks: Callbacks,
    








    private val fileDeleter: (File) -> Unit = { it.delete() },
) {
    init {
        require(recipes.isNotEmpty()) { "DualCandidateVoiceRecorder requires at least one recipe" }
        
        
        
        val seenIds = HashSet<String>(recipes.size)
        require(recipes.all { seenIds.add(it.id) }) {
            "DualCandidateVoiceRecorder recipe ids must be unique"
        }
    }

    interface Callbacks {
        fun onStarted()
        




        fun onStopRequested()
        




        fun onStopped(candidates: List<VoiceMessageRecordingCandidate>)
        fun onCancelled()
        fun onError(message: String)
    }

    private companion object {
        private const val TAG = "DualCandidateVoiceRecorder"
        private const val SAMPLE_RATE = 48_000
        private const val CHANNEL_COUNT = 1
        private const val AAC_BITRATE = 128_000

        
        
        private const val FRAME_SIZE = 480

        
        private const val READ_FRAMES_PER_PULL = 8

        
        
        
        private const val MIN_AUDIO_RECORD_BUFFER_BYTES = FRAME_SIZE * 256 * 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    
    @Volatile
    private var stopping = false
    private var recordJob: Job? = null

    
    @Volatile
    private var audioRecord: AudioRecord? = null

    
    @Volatile
    private var pipeline: OfflineAudioPipeline? = null

    
    private val encoders = LinkedHashMap<String, AacM4aEncoder>()

    
    private val outputFiles = LinkedHashMap<String, File>()

    private var startedAtMs: Long = 0L

    
    private val peakSinceLastRead = AtomicInteger(0)

    val isRunning: Boolean get() = running.get()

    
    fun readPeakAmplitude(): Int = peakSinceLastRead.getAndSet(0)

    @SuppressLint("MissingPermission")
    fun start() {
        
        
        
        
        
        if (!running.compareAndSet(false, true)) return
        cancelled.set(false)
        stopping = false
        startedAtMs = System.currentTimeMillis()
        
        
        
        
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

    
    fun stop() {
        if (!running.get()) return
        L.i { "[$TAG] stop requested" }
        stopping = true
        interruptBlockingRead()
        scope.launch(Dispatchers.Main) { callbacks.onStopRequested() }
    }

    
    fun cancel() {
        if (!running.get()) return
        L.i { "[$TAG] cancel requested" }
        cancelled.set(true)
        stopping = true
        interruptBlockingRead()
    }

    
















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

    




    suspend fun releaseAndJoin() {
        cancel()
        recordJob?.join()
        scope.cancel()
    }

    









    suspend fun awaitResult(): List<VoiceMessageRecordingCandidate>? = resultDeferred.await()

    
    @Volatile
    private var resultDeferred: CompletableDeferred<List<VoiceMessageRecordingCandidate>?> =
        CompletableDeferred<List<VoiceMessageRecordingCandidate>?>().apply { complete(null) }

    

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

        
        
        recorder.startRecording()

        
        
        
        
        val micChannel = Channel<FloatArray>(capacity = Channel.UNLIMITED)

        
        
        
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
            
        }
    }

    private fun updatePeakAmplitude(buf: FloatArray, count: Int) {
        var localPeak = 0
        for (i in 0 until count) {
            
            
            val absInt = (buf[i].absoluteValue * 32767f).toInt().coerceAtMost(32767)
            if (absInt > localPeak) localPeak = absInt
        }
        
        
        
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
            
            
            
            
            
            outputFiles.values.forEach(fileDeleter)
        }
        outputFiles.clear()

        L.i {
            "[$TAG] cleanup end candidates=" +
                candidates.joinToString { "${it.recipe.id}(${it.file?.length() ?: 0}B)" }
        }
        
        
        resultDeferred.complete(if (deleteFiles) null else candidates)
        return candidates
    }

    private fun interruptBlockingRead() {
        
        
        
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
            
            
        }
    }
}
