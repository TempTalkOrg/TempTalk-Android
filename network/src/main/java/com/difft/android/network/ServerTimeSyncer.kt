package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.network.di.ChativeHttpClientModule
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException





@Singleton
class ServerTimeSyncer @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val chatHttpClient: ChativeHttpClient,
) {
    




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
