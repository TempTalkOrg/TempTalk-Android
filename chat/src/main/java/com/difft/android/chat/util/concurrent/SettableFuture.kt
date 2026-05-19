package com.difft.android.chat.util.concurrent

import java.util.LinkedList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Manually-fulfilled [ListenableFuture]. Callers complete the future via [set] or [setException].
 *
 * 1:1 Kotlin port of the historical Java class. Uses `wait()`/`notifyAll()` for blocking [get].
 * The timed [get] preserves the original guard (intentionally left as-is to avoid changing
 * observable behavior during the thread-model migration).
 */
open class SettableFuture<T> : ListenableFuture<T> {

    private val listeners: MutableList<ListenableFuture.Listener<T>> = LinkedList()

    private var completed: Boolean = false
    private var canceled: Boolean = false

    @Volatile private var result: T? = null
    @Volatile private var exception: Throwable? = null

    constructor()

    constructor(value: T) {
        this.result = value
        this.completed = true
    }

    @Synchronized
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        if (!completed && !canceled) {
            canceled = true
            return true
        }
        return false
    }

    @Synchronized
    override fun isCancelled(): Boolean = canceled

    @Synchronized
    override fun isDone(): Boolean = completed

    fun set(result: T): Boolean {
        synchronized(this) {
            if (completed || canceled) return false
            this.result = result
            this.completed = true
            (this as Object).notifyAll()
        }
        notifyAllListeners()
        return true
    }

    fun setException(throwable: Throwable): Boolean {
        synchronized(this) {
            if (completed || canceled) return false
            this.exception = throwable
            this.completed = true
            (this as Object).notifyAll()
        }
        notifyAllListeners()
        return true
    }

    fun deferTo(other: ListenableFuture<T>) {
        other.addListener(object : ListenableFuture.Listener<T> {
            override fun onSuccess(result: T) {
                this@SettableFuture.set(result)
            }

            override fun onFailure(e: ExecutionException) {
                // Match the Java original's `setException(e.getCause())` but fall back to
                // the outer ExecutionException itself if `cause` happens to be null,
                // otherwise our own `exception` field would be left null and the future
                // would incorrectly signal success to listeners.
                this@SettableFuture.setException(e.cause ?: e)
            }
        })
    }

    @Synchronized
    @Throws(InterruptedException::class, ExecutionException::class)
    override fun get(): T {
        while (!completed) (this as Object).wait()

        exception?.let { throw ExecutionException(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Synchronized
    @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
    override fun get(timeout: Long, unit: TimeUnit): T {
        val startTime = System.currentTimeMillis()

        // Note: preserves the pre-existing `>` guard from the Java original.
        // Fixing the timed-get semantics is out of scope for the thread-model migration.
        while (!completed && System.currentTimeMillis() - startTime > unit.toMillis(timeout)) {
            (this as Object).wait(unit.toMillis(timeout))
        }

        if (!completed) throw TimeoutException()
        return get()
    }

    override fun addListener(listener: ListenableFuture.Listener<T>) {
        synchronized(this) {
            listeners.add(listener)
            if (!completed) return
        }
        notifyListener(listener)
    }

    private fun notifyAllListeners() {
        val localListeners: List<ListenableFuture.Listener<T>>
        synchronized(this) {
            localListeners = LinkedList(listeners)
        }
        for (listener in localListeners) notifyListener(listener)
    }

    private fun notifyListener(listener: ListenableFuture.Listener<T>) {
        val ex = exception
        if (ex != null) {
            listener.onFailure(ExecutionException(ex))
        } else {
            @Suppress("UNCHECKED_CAST")
            listener.onSuccess(result as T)
        }
    }
}
