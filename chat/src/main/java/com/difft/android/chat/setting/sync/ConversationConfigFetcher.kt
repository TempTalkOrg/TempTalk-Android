package com.difft.android.chat.setting.sync

import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.GetConversationSetRequestBody
import com.difft.android.network.responses.ConversationSetResponseBody
import javax.inject.Inject
import javax.inject.Singleton







@Singleton
class ConversationConfigFetcher @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient,
) {
    



    suspend fun fetch(conversationIds: List<String>, source: String): List<ConversationSetResponseBody>? {
        if (conversationIds.isEmpty()) return emptyList()
        val chunks = conversationIds.chunked(MAX_IDS_PER_REQUEST)
        if (chunks.size > 1) {
            L.i { "[ConversationGet] event=batch_plan source=$source total=${conversationIds.size} batches=${chunks.size} max=$MAX_IDS_PER_REQUEST" }
        }
        val result = ArrayList<ConversationSetResponseBody>(conversationIds.size)
        chunks.forEachIndexed { index, chunk ->
            val startedAt = System.nanoTime()
            val response = httpClient.httpService.fetchGetConversationSet(GetConversationSetRequestBody(chunk))
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            if (response.status != 0) {
                L.w { "[ConversationGet] event=failed source=$source batch=${index + 1}/${chunks.size} count=${chunk.size} status=${response.status} durationMs=$durationMs" }
                return null
            }
            val conversations = response.data?.conversations.orEmpty()
            L.i { "[ConversationGet] event=succeeded source=$source batch=${index + 1}/${chunks.size} count=${chunk.size} returned=${conversations.size} durationMs=$durationMs" }
            result.addAll(conversations)
        }
        return result
    }

    companion object {
        
        const val MAX_IDS_PER_REQUEST = 200
    }
}
