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

/**
 * Single-worker translation queue (concurrency 1).
 *
 * Execution and observation are deliberately DECOUPLED: the worker publishes every finished job on
 * [completions] regardless of whether anyone is still awaiting the [Deferred] that [submit]
 * returned. A caller that gives up waiting therefore never loses the result — cancelling a wait is
 * not cancelling the work.
 *
 * Priority matters because a conversation can enqueue a screenful of history at the same moment a
 * new message arrives; a plain semaphore would put the new message behind all of it.
 *
 * [generation] is the session epoch. [cancelAll] bumps it, and a job that was already inside the backend
 * carries the old value: its result is still used to complete the [Deferred] — including for a
 * same-key caller that joined it, so nobody is ever left awaiting forever — but it is NOT broadcast
 * on [completions], which is what stops a post-logout result from reaching a page or a database
 * write. Anything that writes on a scope outliving a page reads [generation] and compares it with
 * the value it captured, so a late write can drop itself the same way.
 */
@Singleton
class TranslateQueue @Inject constructor(
    private val engine: TranslateEngine,
) {
    /** Lower ordinal runs first. */
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

    /** Every finished job, for every owner. */
    val completions: SharedFlow<Completion> = _completions.asSharedFlow()

    /**
     * A plain lock rather than a Mutex: [submit] must stay non-suspending so a UI callback can
     * enqueue without a coroutine. Nothing blocking ever runs inside it.
     */
    private val lock = ReentrantLock()
    private val pending = TreeSet<Work>(compareBy({ it.priority.ordinal }, { it.seq }))
    private val byKey = HashMap<String, TranslateWork>()
    private var seq = 0L

    /**
     * Bumped by [cancelAll]. A job that was already inside the backend when the bump happened carries
     * the previous value and has its completion dropped — a backend call cannot be interrupted, so this
     * is what "logout cancels everything" actually means for the one job in flight.
     */
    private var generationCounter = 0L

    /**
     * The current session epoch, for callers that persist on a scope outliving a page: capture it
     * once and re-read it immediately before the write, and a value that moved means logout wiped
     * the database underneath.
     */
    val generation: Long get() = lock.withLock { generationCounter }

    private val signal = Channel<Unit>(Channel.CONFLATED)

    init {
        startWorker()
    }

    /**
     * Enqueues a translation, or joins an identical in-flight one, and returns a handle.
     * Awaiting the handle is optional — see the class comment.
     */
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

    /**
     * Drops [owner]'s not-yet-started jobs and cancels their handles so awaiters can roll back the
     * state they wrote optimistically. A job already running is left alone — a backend call cannot
     * be interrupted — and its result still reaches [completions].
     */
    fun cancelOwner(owner: String) {
        val dropped = lock.withLock { takePending { it.owner == owner } }
        dropped.forEach { it.result.cancel() }
        if (dropped.isNotEmpty()) L.i { "[Translate] cancel owner=$owner dropped=${dropped.size}" }
    }

    /**
     * Logout: drop everything, every owner. The generation bump is the second half — the job already
     * running cannot be interrupted, so instead its result is never broadcast on [completions].
     *
     * Its handle is still completed, so a caller that joined the same key by [submit] gets its
     * [Deferred] resolved normally — it simply never sees the matching completion, and therefore
     * nothing renders it or writes it to the database.
     */
    fun cancelAll() {
        val dropped = lock.withLock {
            generationCounter++
            takePending { true }
        }
        dropped.forEach { it.result.cancel() }
        L.i { "[Translate] cancel all dropped=${dropped.size}" }
    }

    /**
     * Runs [block] on the worker, i.e. with no translation in flight. Used by
     * [TranslateEngine.releaseAll] so backend clients are never closed mid-translation.
     */
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
                        // There is exactly ONE worker: letting an unexpected failure escape would
                        // stop every translation for the rest of the process.
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
            // A cancelled backend task cancels the CONTINUATION, not the worker's job. Rethrowing
            // it regardless would kill the single worker and stop translating for the whole
            // process, so only a genuine worker cancellation propagates.
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
            // Broadcast BEFORE completing the handle: the broadcast must happen even when the
            // awaiter has already given up and cancelled its side.
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
        // Completed either way, so an awaiter is never left hanging on a dropped generation.
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
        /** Releasing memory must not queue behind a backlog of history translations. */
        override val priority: Priority = Priority.NEW_INCOMING
    }

    private companion object {
        const val COMPLETION_BUFFER = 64
    }
}
