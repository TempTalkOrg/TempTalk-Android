package com.difft.android.chat.translate

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TreeSet
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock



















@Singleton
class TranslateQueue @Inject constructor(
    private val engine: TranslateEngine,
) {
    
    enum class Priority { NEW_INCOMING, VISIBLE, BACKGROUND }

    data class Completion(
        val owner: String,
        val key: String,
        val messageId: String,
        val target: TranslateLang,
        val result: TranslateResult,
    )

    private val _completions = MutableSharedFlow<Completion>(
        replay = 0,
        extraBufferCapacity = COMPLETION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    
    val completions: SharedFlow<Completion> = _completions.asSharedFlow()

    



    private val lock = ReentrantLock()
    private val pending = TreeSet<Work>(compareBy({ it.priority.ordinal }, { it.seq }))
    private val byKey = HashMap<String, TranslateWork>()
    private var seq = 0L

    




    private var generationCounter = 0L

    




    val generation: Long get() = lock.withLock { generationCounter }

    private val signal = Channel<Unit>(Channel.CONFLATED)

    init {
        startWorker()
    }

    



    fun submit(
        owner: String,
        key: String,
        messageId: String,
        priority: Priority,
        text: String,
        target: TranslateLang,
        mode: TranslateMode,
    ): Deferred<TranslateResult> {
        val work = lock.withLock {
            byKey[key] ?: TranslateWork(
                owner = owner,
                key = key,
                messageId = messageId,
                priority = priority,
                seq = seq++,
                generation = generationCounter,
                text = text,
                target = target,
                mode = mode,
            ).also {
                byKey[key] = it
                pending.add(it)
            }
        }
        signal.trySend(Unit)
        return work.result
    }

    




    fun cancelOwner(owner: String) {
        val dropped = lock.withLock { takePending { it.owner == owner } }
        dropped.forEach { it.result.cancel() }
        if (dropped.isNotEmpty()) L.i { "[Translate] cancel owner=$owner dropped=${dropped.size}" }
    }

    







    fun cancelAll() {
        val dropped = lock.withLock {
            generationCounter++
            takePending { true }
        }
        dropped.forEach { it.result.cancel() }
        L.i { "[Translate] cancel all dropped=${dropped.size}" }
    }

    



    suspend fun barrier(block: suspend () -> Unit) {
        val work = lock.withLock {
            BarrierWork(seq = seq++, block = block).also { pending.add(it) }
        }
        signal.trySend(Unit)
        work.done.await()
    }

    private fun takePending(predicate: (TranslateWork) -> Boolean): List<TranslateWork> {
        val dropped = pending.filterIsInstance<TranslateWork>().filter(predicate)
        dropped.forEach {
            pending.remove(it)
            byKey.remove(it.key)
        }
        return dropped
    }

    private fun startWorker() {
        appScope.launch(Dispatchers.Default) {
            while (isActive) {
                signal.receive()
                while (true) {
                    val work = lock.withLock { pending.pollFirst() } ?: break
                    try {
                        when (work) {
                            is TranslateWork -> runTranslation(work)
                            is BarrierWork -> runBarrier(work)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        
                        
                        L.e { "[Translate] worker error: ${e.stackTraceToString()}" }
                    }
                }
            }
        }
    }

    private suspend fun runTranslation(work: TranslateWork) {
        val result = try {
            engine.translate(work.text, work.target, work.mode)
        } catch (e: CancellationException) {
            
            
            
            if (!currentCoroutineContext().isActive) throw e
            L.w { "[Translate] task cancelled msg=${work.messageId}" }
            TranslateResult.Failed(e)
        } catch (e: Exception) {
            L.e { "[Translate] engine error msg=${work.messageId}: ${e.stackTraceToString()}" }
            TranslateResult.Failed(e)
        }
        val stale = lock.withLock {
            byKey.remove(work.key)
            work.generation != generationCounter
        }
        if (stale) {
            L.i { "[Translate] completion dropped msg=${work.messageId} reason=cancelAll" }
        } else {
            
            
            _completions.tryEmit(
                Completion(
                    owner = work.owner,
                    key = work.key,
                    messageId = work.messageId,
                    target = work.target,
                    result = result,
                )
            )
        }
        
        work.result.complete(result)
    }

    private suspend fun runBarrier(work: BarrierWork) {
        try {
            work.block()
        } catch (e: CancellationException) {
            work.done.cancel()
            throw e
        } catch (e: Exception) {
            L.w { "[Translate] barrier failed: ${e.stackTraceToString()}" }
        }
        work.done.complete(Unit)
    }

    private sealed interface Work {
        val priority: Priority
        val seq: Long
    }

    private class TranslateWork(
        val owner: String,
        val key: String,
        val messageId: String,
        override val priority: Priority,
        override val seq: Long,
        val generation: Long,
        val text: String,
        val target: TranslateLang,
        val mode: TranslateMode,
        val result: CompletableDeferred<TranslateResult> = CompletableDeferred(),
    ) : Work

    private class BarrierWork(
        override val seq: Long,
        val block: suspend () -> Unit,
        val done: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : Work {
        
        override val priority: Priority = Priority.NEW_INCOMING
    }

    private companion object {
        const val COMPLETION_BUFFER = 64
    }
}
