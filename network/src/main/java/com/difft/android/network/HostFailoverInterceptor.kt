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
            
            val answeredBy = attempt.state.hopHost ?: response.request.url.host
            if (response.code < SERVER_ERROR) {
                urlManager.recordHealthyHost(answeredBy)
                return Step.Done(observed(response, answeredBy))
            }

            urlManager.recordFailedHost(answeredBy)
            
            
            val replayable = request.body?.isOneShot() != true
            val next = if (resendOnServerError && replayable) nextHost() else null
            logFailover(KIND_SERVER_ERROR, answeredBy, next, response.code)
            if (next != null) {
                response.close()
                return Step.Resend(next)
            }
            
            
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
                
                
                
                
                state.hopSent &&
                    (error === state.observedError || request.method !in IDEMPOTENT_METHODS) -> {
                    urlManager.recordFailedHost(failedHost)
                    logFailover(KIND_POST_SEND, failedHost, next = null, error = error)
                    throw error
                }

                
                state.hopIndex > 0 -> throw error

                
                !urlManager.isNetworkAvailable() -> throw error

                else -> {
                    urlManager.recordFailedHost(attempt.host)
                    val next = nextHost()
                    logFailover(KIND_CONNECT, attempt.host, next, error = error)
                    Step.Resend(next ?: throw error)
                }
            }
        }

        




        private fun boundedChain(isResend: Boolean): Interceptor.Chain {
            if (!isResend) return chain
            val remaining = (deadline - elapsedMs()).coerceAtLeast(1L)
            if (remaining >= chain.connectTimeoutMillis()) return chain
            return chain.withConnectTimeout(remaining.toInt(), TimeUnit.MILLISECONDS)
        }

        
        private fun nextHost(): String? {
            if (elapsedMs() >= deadline) return null
            return urlManager.getAllHostsRanked().firstOrNull { it !in attempted }
        }

        
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

        




        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
