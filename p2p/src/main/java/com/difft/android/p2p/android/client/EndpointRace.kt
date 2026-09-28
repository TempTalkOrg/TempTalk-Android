package com.difft.android.p2p.android.client

import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.android.tls.FingerprintTrustManager
import com.difft.android.p2p.engine.model.QrPayload
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Picks which address of a displayer to talk to when its code advertises several: every candidate
 * is tried at once — TCP connect, TLS handshake, certificate pinned to the code's fingerprint —
 * and the first to complete all three wins; the rest are closed. The HTTP client's own connection
 * fallback races TCP only, so a peer that accepts but never completes the handshake would stall it.
 *
 * One deadline covers TCP and TLS together, because `startHandshake()` blocks with no clock of its
 * own: a watchdog closes every socket still open at the deadline, on a winner, or on cancellation.
 * Nothing here logs the PIN or the transfer key.
 */
object EndpointRace {
    /** Budget for TCP + TLS per candidate; all candidates run inside the same window. */
    const val BUDGET_MS = 3_000L

    /**
     * [target] with its address settled: the winner in `ip`, alone in `ips`. A code with one address
     * is returned as is, letting the ordinary connection decide. Null when every candidate failed
     * within the budget.
     */
    suspend fun resolve(target: QrPayload, budgetMs: Long = BUDGET_MS): QrPayload? {
        val candidates = target.candidates.distinct()
        if (candidates.size <= 1) return target.copy(ip = candidates.first(), ips = candidates)
        val winner = race(candidates, target.port, target.fp, budgetMs) ?: return null
        return target.copy(ip = winner, ips = listOf(winner))
    }

    /** The first of [candidates] to complete TCP + TLS + pin against [fingerprint], or null. */
    suspend fun race(candidates: List<String>, port: Int, fingerprint: String, budgetMs: Long = BUDGET_MS): String? = coroutineScope {
        val started = System.nanoTime()
        val tm = FingerprintTrustManager(fingerprint)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        val sockets = candidates.map { ssl.socketFactory.createSocket() as SSLSocket }
        val winner = CompletableDeferred<String?>()
        val pending = AtomicInteger(candidates.size)
        fun closeAll() = sockets.forEach { runCatching { it.close() } }

        val probes = candidates.mapIndexed { i, ip ->
            launch(Dispatchers.IO) {
                val socket = sockets[i]
                val outcome = runCatching {
                    socket.connect(InetSocketAddress(ip, port), budgetMs.toInt())
                    socket.startHandshake()
                }
                outcome.onSuccess {
                    L.i { "[P2P][Race] $ip won in ${elapsedMs(started)}ms" }
                    winner.complete(ip)
                }.onFailure { e ->
                    L.i { "[P2P][Race] $ip failed: ${e.javaClass.simpleName}" }
                    if (pending.decrementAndGet() == 0) winner.complete(null)
                }
                runCatching { socket.close() }
            }
        }
        val watchdog = launch {
            delay(budgetMs)
            L.w { "[P2P][Race] budget of ${budgetMs}ms elapsed with no winner; closing ${candidates.size} probes" }
            closeAll()
        }
        try {
            winner.await()
        } finally {
            watchdog.cancel()
            closeAll()
            probes.forEach { it.cancel() }
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI

    private const val NANOS_PER_MILLI = 1_000_000L
}
