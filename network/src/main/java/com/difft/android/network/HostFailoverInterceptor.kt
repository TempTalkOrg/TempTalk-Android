package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.network.CertValidationFailureDetector
import com.difft.android.base.network.NetworkRiskNotifier
import com.difft.android.base.utils.application
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host failover for chat-pool calls (#1396): marks the host that actually failed so the next
 * request goes elsewhere, and resends the current call to the next eligible host when that is
 * safe. Applies only to calls tagged by [ChatPoolRoutingInterceptor]; any other request passes
 * through untouched (#948).
 *
 * Installed closest to the network, inside [HttpClientInterceptor]: it classifies the raw response
 * or exception before normalisation turns it into a [NetworkException], and [HeaderInterceptor]'s
 * 401 retry stays outside it (#1231).
 *
 * | Outcome on host H | Mark H | Resend this call |
 * |---|---|---|
 * | IOException before the request was handed to a connection (DNS, connect, TLS, certificate) | yes | next host, unless the device has no network |
 * | a connect failure after OkHttp retried an exchange that had reached a connection | yes | next host for a read-only method (GET/HEAD/OPTIONS) only |
 * | IOException after the request was sent (read timeout, reset) | yes | no |
 * | 5xx | yes | next host, unless [resendOnServerError] is false |
 * | any other response | no, and clears a mark on H | no |
 * | cancellation, [NetworkException] | no | no |
 *
 * Marks go through [UrlManager.recordFailedHost], which drops them while the device has no network
 * and for hosts outside the chat pool. Each host is tried once and the whole call is bounded by
 * [totalBoundMs], which also caps the connect timeout of each resend. A body that fails while it is
 * read marks its host too, with no resend.
 *
 * @param resendOnServerError false for the Signal API client, which hands raw statuses to its
 * repositories: its 5xx is marked but returned as is.
 * @param pinnedConnection true when trust is pinned to the embedded CA, where a certificate failure
 * is reported to [NetworkRiskNotifier] for every attempted host (#983).
 */
class HostFailoverInterceptor(
    internal val resendOnServerError: Boolean,
    private val pinnedConnection: Boolean,
    private val totalBoundMs: Long = DEFAULT_TOTAL_BOUND_MS,
    private val urlManagerProvider: () -> UrlManager = {
        EntryPointAccessors.fromApplication<EntryPoint>(application).urlManager
    },
    private val elapsedMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) : Interceptor {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val urlManager: UrlManager
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val routed = request.tag(RoutedHost::class.java) ?: return chain.proceed(request)
        return FailoverCall(chain, request, routed).run()
    }

    /** What one attempt on [host] produced, with the hop state its network interceptor recorded. */
    private sealed interface Attempt {
        val host: String
        val state: AttemptState

        class Answered(override val host: String, override val state: AttemptState, val response: Response) : Attempt
        class Failed(override val host: String, override val state: AttemptState, val error: IOException) : Attempt
    }

    private sealed interface Step {
        class Done(val response: Response) : Step
        class Resend(val host: String) : Step
    }

    /** The state of one intercepted call: which hosts were tried and the last real exception. */
    private inner class FailoverCall(
        private val chain: Interceptor.Chain,
        private val request: Request,
        private val routed: RoutedHost,
    ) {
        private val urlManager = urlManagerProvider()
        private val call: Call = chain.call()
        private val deadline = elapsedMs() + totalBoundMs
        private val attempted = LinkedHashSet<String>()

        fun run(): Response {
            var host = request.url.host
            while (true) {
                attempted += host
                val step = when (val attempt = attemptOn(host)) {
                    is Attempt.Answered -> onAnswered(attempt)
                    is Attempt.Failed -> onFailed(attempt)
                }
                when (step) {
                    is Step.Done -> return step.response
                    is Step.Resend -> host = step.host
                }
            }
        }

        private fun attemptOn(host: String): Attempt {
            val state = AttemptState()
            val url = if (host == request.url.host) request.url else request.url.newBuilder().host(host).build()
            val attemptRequest = request.newBuilder().url(url).tag(AttemptState::class.java, state).build()
            return try {
                Attempt.Answered(host, state, boundedChain(isResend = attempted.size > 1).proceed(attemptRequest))
            } catch (e: IOException) {
                Attempt.Failed(host, state, e)
            }
        }

        private fun onAnswered(attempt: Attempt.Answered): Step {
            val response = attempt.response
            // The hop that answered: the redirect target when OkHttp followed one.
            val answeredBy = attempt.state.hopHost ?: response.request.url.host
            if (response.code < SERVER_ERROR) {
                urlManager.recordHealthyHost(answeredBy)
                return Step.Done(observed(response, answeredBy))
            }

            urlManager.recordFailedHost(answeredBy)
            // A one-shot body was consumed by this attempt; resending it would fail on the next
            // host and wrongly mark that host.
            val replayable = request.body?.isOneShot() != true
            val next = if (resendOnServerError && replayable) nextHost() else null
            logFailover(KIND_SERVER_ERROR, answeredBy, next, response.code)
            if (next != null) {
                response.close()
                return Step.Resend(next)
            }
            // Out of hosts (or a raw-status client): this host's 5xx is the result, even after an
            // earlier connect failure, so a raw-status repository still sees the real status.
            return Step.Done(observed(response, answeredBy))
        }

        private fun onFailed(attempt: Attempt.Failed): Step {
            val error = attempt.error
            if (call.isCanceled() || error is NetworkException) throw error
            val state = attempt.state
            val failedHost = state.hopHost ?: attempt.host
            if (pinnedConnection && CertValidationFailureDetector.isCertValidationFailure(error)) {
                NetworkRiskNotifier.onCertValidationFailed("http:$failedHost")
            }
            return when {
                // The request reached a connection, so it may have been delivered: never resend. One
                // exception: OkHttp retried that exchange on a new connection and the retry failed at
                // connect (a different exception than the observed one). The earlier exchange may still
                // have been delivered, so only a request that is safe to repeat goes on to the next host.
                state.hopSent &&
                    (error === state.observedError || request.method !in IDEMPOTENT_METHODS) -> {
                    urlManager.recordFailedHost(failedHost)
                    logFailover(KIND_POST_SEND, failedHost, next = null, error = error)
                    throw error
                }

                // A redirect follow-up failed before it connected: the target is not attributable.
                state.hopIndex > 0 -> throw error

                // Every host fails the same way while the device is offline: nothing to learn or try.
                !urlManager.isNetworkAvailable() -> throw error

                else -> {
                    urlManager.recordFailedHost(attempt.host)
                    val next = nextHost()
                    logFailover(KIND_CONNECT, attempt.host, next, error = error)
                    Step.Resend(next ?: throw error)
                }
            }
        }

        /**
         * A resend may not spend longer opening a connection than the failover budget has left. Only
         * the connect stage is bounded: once a connection exists, the client's own read and write
         * timeouts govern, so a long upload or download on the backup host is not cut short.
         */
        private fun boundedChain(isResend: Boolean): Interceptor.Chain {
            if (!isResend) return chain
            val remaining = (deadline - elapsedMs()).coerceAtLeast(1L)
            if (remaining >= chain.connectTimeoutMillis()) return chain
            return chain.withConnectTimeout(remaining.toInt(), TimeUnit.MILLISECONDS)
        }

        /** The best eligible host not tried yet, or null when none is left or time is up. */
        private fun nextHost(): String? {
            if (elapsedMs() >= deadline) return null
            return urlManager.getAllHostsRanked().firstOrNull { it !in attempted }
        }

        /** Wraps [response] so a failure while its body is read marks [host] (post-send, no resend). */
        private fun observed(response: Response, host: String): Response {
            val marked = AtomicBoolean(false)
            val body = FailureObservingBody(response.body) { error ->
                if (!call.isCanceled() && marked.compareAndSet(false, true)) {
                    urlManager.recordFailedHost(host)
                    logFailover(KIND_POST_SEND, host, next = null, error = error)
                }
            }
            return response.newBuilder().body(body).build()
        }

        private fun logFailover(kind: String, host: String, next: String?, code: Int? = null, error: IOException? = null) {
            val cause = error?.let { " cause=${it.javaClass.simpleName}" } ?: code?.let { " code=$it" }.orEmpty()
            L.w { "[HostFailover] class=$kind host=$host service=${routed.service} next=${next ?: "none"} attempt=${attempted.size}$cause" }
        }
    }

    private companion object {
        const val DEFAULT_TOTAL_BOUND_MS = 30_000L
        const val NANOS_PER_MS = 1_000_000L
        const val SERVER_ERROR = 500
        const val KIND_CONNECT = "connect"
        const val KIND_POST_SEND = "postsend"
        const val KIND_SERVER_ERROR = "5xx"

        /**
         * Read-only methods, safe to replay after a possible delivery. PUT and DELETE are left out:
         * the server uses PUT for writes that are not idempotent (e.g. `PUT v1/groups` creates a
         * group and returns its gid).
         */
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
