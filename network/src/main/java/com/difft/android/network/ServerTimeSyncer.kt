package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.network.di.ChativeHttpClientModule
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Best-effort fallback trigger for [ServerTimeProvider], covering cold start / pre-archive when the
 * primary anchor source (the API-response hook) hasn't run yet. Uses the @Chat client (serves /v1/health).
 */
@Singleton
class ServerTimeSyncer @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val chatHttpClient: ChativeHttpClient,
) {
    /**
     * Anchor once via /v1/health if not already anchored; the converter hook captures the envelope's
     * serverTimestamp. Best-effort: failure/timeout leaves the provider on its L2/L3 fallback.
     * CancellationException is rethrown — do NOT switch to runCatching (it would swallow the timeout cancel).
     */
    suspend fun ensureAnchored() {
        if (ServerTimeProvider.isAnchored()) return
        withTimeoutOrNull(3_000) {
            try {
                chatHttpClient.httpService.health()
                if (!ServerTimeProvider.isAnchored()) {
                    L.w { "[ServerTime] health returned but provider not anchored — check response envelope serverTimestamp" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[ServerTime] health failed: ${e.message}" }
            }
        }
    }
}
