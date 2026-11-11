package com.difft.android.network.proxy

import android.os.Looper
import com.difft.android.base.log.lumberjack.L
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton








@Singleton
class ProxyHttpConnectionRegistry @Inject constructor() {

    private val clients = mutableListOf<WeakReference<OkHttpClient>>()
    private val lock = Any()

    
    fun register(client: OkHttpClient) {
        synchronized(lock) {
            
            clients.removeAll { it.get() == null }
            if (clients.none { it.get() === client }) {
                clients.add(WeakReference(client))
            }
        }
    }

    



    private val mainThreadEvictor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "proxy-http-evict").apply { isDaemon = true }
        }
    }

    












    fun evictAll() {
        val mainLooper = Looper.getMainLooper()
        if (mainLooper != null && Looper.myLooper() === mainLooper) {
            val caller = callerFrame()
            L.w { "[Proxy] evictAll called on main thread, moved off main caller=$caller" }
            mainThreadEvictor.execute { evictNow() }
            return
        }
        evictNow()
    }

    private fun callerFrame(): String =
        Throwable().stackTrace
            .firstOrNull { it.className != ProxyHttpConnectionRegistry::class.java.name }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            ?: "unknown"

    private fun evictNow() {
        val snapshot = synchronized(lock) {
            clients.removeAll { it.get() == null }
            clients.mapNotNull { it.get() }
        }
        val thread = Thread.currentThread().name
        snapshot.forEach { client ->
            runCatching { client.connectionPool.evictAll() }
                .onFailure {
                    L.w { "[Proxy] evict http pool failed thread=$thread ${it.stackTraceToString()}" }
                }
        }
        L.i { "[Proxy] evicted ${snapshot.size} http client pool(s) after proxy change" }
    }
}
