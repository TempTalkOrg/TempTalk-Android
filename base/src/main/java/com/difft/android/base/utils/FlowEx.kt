package com.difft.android.base.utils





import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.whileSelect















@ExperimentalCoroutinesApi
public fun <T> Flow<T>.chunked(method: ChunkingMethod): Flow<List<T>> = with(method) { chunk() }

@ExperimentalCoroutinesApi
public interface ChunkingMethod {
    public fun <T> Flow<T>.chunk(): Flow<List<T>>

    public companion object {

        









        @Suppress("FunctionName")
        public fun Natural(maxSize: Int = Int.MAX_VALUE): ChunkingMethod = NaturalChunking(maxSize)

        









        @Suppress("FunctionName")
        public fun ByTime(intervalMs: Long, maxSize: Int = Int.MAX_VALUE): ChunkingMethod =
            TimeBased(intervalMs, maxSize)

        










        @Suppress("FunctionName")
        public fun ByTimeOrSize(intervalMs: Long, maxSize: Int): ChunkingMethod = TimeOrSizeBased(intervalMs, maxSize)

        






        @Suppress("FunctionName")
        public fun BySize(size: Int): ChunkingMethod = SizeBased(size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class NaturalChunking(private val maxSize: Int) : ChunkingMethod {

    init {
        requirePositive(maxSize)
    }

    @OptIn(FlowPreview::class)
    override fun <T> Flow<T>.chunk(): Flow<List<T>> = scopedFlowLocal { downstream ->
        val upstream = buffer(maxSize).produceIn(this)

        while (!upstream.isClosedForReceive) {
            val chunk = upstream.awaitFirstAndDrain(maxSize)
            if (chunk.isNotEmpty()) downstream.emit(chunk)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class TimeBased(private val intervalMs: Long, private val maxSize: Int) : ChunkingMethod {

    init {
        requirePositive(intervalMs)
        requirePositive(maxSize)
    }

    override fun <T> Flow<T>.chunk(): Flow<List<T>> = channelFlow {
        val upstreamCollection = Job()
        val upstream = produce<T>(capacity = maxSize) {
            collect { element -> send(element) }
            upstreamCollection.complete()
        }

        var lastEmitTime = System.currentTimeMillis()
        
        whileSelect {
            upstreamCollection.onJoin {
                delay(intervalMs)
                val chunk = upstream.drain(maxElements = maxSize)
                if (chunk.isNotEmpty()) {
                    channel.send(chunk)
                    lastEmitTime = System.currentTimeMillis()
                }
                false
            }

            onTimeout(intervalMs) {
                val chunk = upstream.drain(maxElements = maxSize)
                if (chunk.isNotEmpty()) {
                    channel.send(chunk)
                    lastEmitTime = System.currentTimeMillis()
                }
                true
            }
        }
    }
}


@OptIn(ExperimentalCoroutinesApi::class)
private class SizeBased(private val size: Int) : ChunkingMethod {

    init {
        requirePositive(size)
    }

    @ExperimentalCoroutinesApi
    override fun <T> Flow<T>.chunk(): Flow<List<T>> = flow {
        val accumulator = ArrayList<T>(size)
        collect { element ->
            accumulator.add(element)
            if (accumulator.size == size) emit(accumulator.drain())
        }
        if (accumulator.isNotEmpty()) emit(accumulator)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class TimeOrSizeBased(private val intervalMs: Long, private val maxSize: Int) : ChunkingMethod {

    init {
        requirePositive(intervalMs)
        requirePositive(maxSize)
    }

    override fun <T> Flow<T>.chunk(): Flow<List<T>> = scopedFlowLocal { downstream ->
        val emitNowAndMaybeContinue = Channel<Boolean>(capacity = Channel.RENDEZVOUS)
        val elements = produce<T>(capacity = maxSize) {
            collect { element ->
                val hasCapacity = channel.trySend(element).isSuccess
                if (!hasCapacity) {
                    emitNowAndMaybeContinue.send(true)
                    channel.send(element)
                }
            }
            emitNowAndMaybeContinue.send(false)
        }

        whileSelect {
            emitNowAndMaybeContinue.onReceive { shouldContinue ->
                val chunk = elements.drain(maxElements = maxSize)
                if (chunk.isNotEmpty()) downstream.emit(chunk)
                shouldContinue
            }

            onTimeout(intervalMs) {
                val chunk = elements.drain(maxElements = maxSize)
                if (chunk.isNotEmpty()) downstream.emit(chunk)
                true
            }
        }
    }

}

private suspend fun <T> ReceiveChannel<T>.awaitFirstAndDrain(maxElements: Int): List<T> = try {
    val first = receive()
    drain(mutableListOf(first), maxElements)
} catch (e: ClosedReceiveChannelException) {
    emptyList()
}


private tailrec fun <T> ReceiveChannel<T>.drain(acc: MutableList<T> = mutableListOf(), maxElements: Int): List<T> =
    if (acc.size == maxElements) acc
    else {
        val nextValue = tryReceive().getOrElse { error: Throwable? -> error?.let { throw(it) } ?: return acc }
        acc.add(nextValue)
        drain(acc, maxElements)
    }

private fun <T> MutableList<T>.drain() = toList().also { this.clear() }

private fun requirePositive(size: Int) = require(size > 0)

private fun requirePositive(intervalMs: Long) = require(intervalMs > 0)

@ExperimentalCoroutinesApi
private inline fun <T> scopedFlowLocal(
    crossinline block: suspend CoroutineScope.(FlowCollector<List<T>>) -> Unit
): Flow<List<T>> = flow {
    coroutineScope {
        block(this, this@flow)
    }
}
