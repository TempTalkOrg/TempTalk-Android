package com.difft.android.base.utils

/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

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

/**
 * Groups emissions from this Flow into lists, according to the chosen ChunkingMethod. Time based implementations
 * collect upstream and emit to downstream in separate coroutines - concurrently, like Flow.buffer() operator.
 * Exact timing of emissions is not guaranteed, as it depends on collector coroutine availability.
 *
 * Size based chunking happens in a single coroutine and is purely sequential.
 *
 * Emissions always preserve order.
 *
 * It is possible to pass custom implementation of ChunkingMethod to chunked() operator.
 *
 * @param method Defines constrains on chunk size and time of its emission.
 */

@ExperimentalCoroutinesApi
public fun <T> Flow<T>.chunked(method: ChunkingMethod): Flow<List<T>> = with(method) { chunk() }

@ExperimentalCoroutinesApi
public interface ChunkingMethod {
    public fun <T> Flow<T>.chunk(): Flow<List<T>>

    public companion object {

        /**
         * Collects upstream and emits to downstream in separate coroutines - as soon as possible. If consumer keeps
         * up with the producer, it emits lists with single element.
         *
         * In case of slow consumer, it groups emissions into bigger lists. When consumer "speeds up", chunks
         * will get smaller.
         *
         * @param maxSize Maximum size of a single chunk. If reached, producer gets suspended until consumer "consumes"
         * a chunk. If maxSize is not specified, then chunk may grow indefinitely until jvm runs out of memory.
         */
        @Suppress("FunctionName")
        public fun Natural(maxSize: Int = Int.MAX_VALUE): ChunkingMethod = NaturalChunking(maxSize)

        /**
         * Collects upstream into a buffer and emits its content as a list at every interval. When upstream completes
         * (or is empty), it will try to emit immediately what is left of a chunk, omitting the interval.
         *
         * @param intervalMs Interval between emissions in milliseconds. Every emission happens only after
         * interval passes, unless upstream Flow completes sooner.
         *
         * @param maxSize Maximum size of a single chunk. If reached, producer gets suspended until consumer "consumes"
         * a chunk. If maxSize is not specified, then chunk may grow indefinitely until jvm runs out of memory.
         */
        @Suppress("FunctionName")
        public fun ByTime(intervalMs: Long, maxSize: Int = Int.MAX_VALUE): ChunkingMethod =
            TimeBased(intervalMs, maxSize)

        /**
         * Collects upstream into a buffer and emits its content as a list at every interval or when its buffer reaches
         * maximum size. When upstream completes (or is empty), it will try to emit immediately what is left of
         * a chunk, omitting the interval and maxSize constraints.
         *
         * @param intervalMs Interval between emissions in milliseconds. Every emission happens only after
         * interval passes, unless upstream Flow completes sooner or maximum size of a chunk is reached.
         *
         * @param maxSize Maximum size of a single chunk. If reached, it will try to emit a chunk, ignoring the
         * interval constraint. If so happens, time-to-next-chunk gets reset to the interval value.
         */
        @Suppress("FunctionName")
        public fun ByTimeOrSize(intervalMs: Long, maxSize: Int): ChunkingMethod = TimeOrSizeBased(intervalMs, maxSize)

        /**
         * Collects upstream into a buffer and emits its content as a list, when specified size is reached.
         * This implementation is purely sequential. If concurrent upstream collection and downstream emissions are
         * desired, one can use a buffer() operator after chunking
         *
         * @param size Exact size of emitted chunks. Only the last emission may be smaller.
         */
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
