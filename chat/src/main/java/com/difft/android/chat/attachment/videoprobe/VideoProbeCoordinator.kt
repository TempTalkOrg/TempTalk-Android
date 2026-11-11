package com.difft.android.chat.attachment.videoprobe

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.VideoProbeState
import difft.android.messageserialization.model.isWhitelistedVideoType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton


data class VideoProbeResult(val attachmentKey: String, val databaseId: Int)














@Singleton
class VideoProbeCoordinator internal constructor(
    private val store: VideoProbeStore,
    private val prober: VideoFrameProber,
    private val scope: CoroutineScope,
    private val decodeTimeoutMs: Long,
) {

    @Inject
    constructor(store: VideoProbeStore, prober: VideoFrameProber) : this(
        store,
        prober,
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("VideoProbe")),
        DECODE_TIMEOUT_MS,
    )

    private val permit = Semaphore(1)

    
    private val pendingKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    
    private val inFlight: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    
    private val abandoned = mutableListOf<Thread>()

    @Volatile
    private var stopped = false

    private val _results = MutableSharedFlow<VideoProbeResult>(
        extraBufferCapacity = RESULT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    
    val results: SharedFlow<VideoProbeResult> = _results.asSharedFlow()

    
    fun request(attachment: Attachment, basePath: String) {
        if (stopped) return
        val key = attachment.localId
        if (!pendingKeys.add(key)) return
        scope.launch {
            try {
                probe(key, attachment.fileName.orEmpty(), basePath)
            } finally {
                pendingKeys.remove(key)
            }
        }
    }

    
    suspend fun readRow(databaseId: Int): VideoProbeRow? = withContext(Dispatchers.IO) {
        runCatching { store.read(databaseId) }
            .onFailure { L.w { "[VideoProbe] read failed id=$databaseId error=${it.javaClass.simpleName}" } }
            .getOrNull()
    }

    internal suspend fun probe(key: String, fileName: String, basePath: String) = permit.withPermit {
        if (breakerOpen()) return@withPermit
        try {
            probeLocked(key, fileName, basePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[VideoProbe] probe error key=$key ${e.stackTraceToString()}" }
        }
    }

    private suspend fun probeLocked(key: String, fileName: String, basePath: String) {
        val row = store.resolve(key, fileName)
        if (row == null) {
            L.w { "[VideoProbe] row unresolved key=$key" }
            return
        }
        val id = row.databaseId
        if (row.width > 0 && row.height > 0) {
            
            _results.emit(VideoProbeResult(key, id))
            return
        }
        if (id in inFlight) return
        val attemptState = when (row.videoProbeState) {
            VideoProbeState.NONE -> VideoProbeState.IN_PROGRESS_1
            VideoProbeState.IN_PROGRESS_1 -> VideoProbeState.IN_PROGRESS_2
            VideoProbeState.IN_PROGRESS_2 -> {
                L.w { "[VideoProbe] interrupted twice id=$id result=failed" }
                store.writeFailedIfState(id, VideoProbeState.IN_PROGRESS_2)
                _results.emit(VideoProbeResult(key, id))
                return
            }
            else -> return
        }
        inFlight.add(id)
        try {
            store.writeState(id, attemptState)
            val startedAt = System.currentTimeMillis()
            val outcome = decodeWithTimeout(basePath)
            val costMs = System.currentTimeMillis() - startedAt
            if (outcome is DecodeOutcome.Sized) {
                store.writeSize(id, outcome.size.width, outcome.size.height)
            } else {
                store.writeFailedIfState(id, attemptState)
            }
            L.i { "[VideoProbe] done id=$id attempt=$attemptState result=${outcome.label} costMs=$costMs" }
            _results.emit(VideoProbeResult(key, id))
        } finally {
            inFlight.remove(id)
        }
    }

    private suspend fun decodeWithTimeout(basePath: String): DecodeOutcome {
        val done = CompletableDeferred<DecodeOutcome>()
        val thread = Thread({
            val outcome = try {
                prober.probe(basePath)?.let { DecodeOutcome.Sized(it) } ?: DecodeOutcome.Undecodable(null)
            } catch (t: Throwable) {
                
                DecodeOutcome.Undecodable(t.javaClass.simpleName)
            }
            done.complete(outcome)
        }, "VideoProbe-decode").apply { isDaemon = true }
        thread.start()
        return withTimeoutOrNull(decodeTimeoutMs) { done.await() } ?: run {
            abandoned.add(thread)
            breakerOpen()
            DecodeOutcome.TimedOut
        }
    }

    
    private fun breakerOpen(): Boolean {
        if (stopped) return true
        abandoned.removeAll { !it.isAlive }
        if (abandoned.size >= MAX_ABANDONED_THREADS) {
            stopped = true
            L.w { "[VideoProbe] stopped for this process abandonedThreads=${abandoned.size}" }
        }
        return stopped
    }

    private sealed interface DecodeOutcome {
        val label: String

        data class Sized(val size: VideoSize) : DecodeOutcome {
            override val label get() = "sized"
        }

        data class Undecodable(val error: String?) : DecodeOutcome {
            override val label get() = if (error == null) "undecodable" else "error:$error"
        }

        data object TimedOut : DecodeOutcome {
            override val label get() = "timeout"
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CoordinatorEntryPoint {
        fun videoProbeCoordinator(): VideoProbeCoordinator
    }

    companion object {
        const val DECODE_TIMEOUT_MS = 10_000L
        const val MAX_ABANDONED_THREADS = 2
        private const val RESULT_BUFFER = 64

        
        fun from(context: Context): VideoProbeCoordinator =
            EntryPointAccessors.fromApplication(context.applicationContext, CoordinatorEntryPoint::class.java)
                .videoProbeCoordinator()

        




        fun needsProbe(attachment: Attachment, isFileReadable: Boolean): Boolean =
            isFileReadable && isWhitelistedVideoType(attachment.contentType) &&
                (attachment.width <= 0 || attachment.height <= 0)
    }
}
