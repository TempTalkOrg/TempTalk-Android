package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.SetPriorConversationRequestBody
import dagger.Lazy
import difft.android.messageserialization.For
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton










@Singleton
class PriorConversationReporter @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val httpClient: Lazy<ChativeHttpClient>,
) {
    
    
    
    
    private val lastReported = AtomicReference<Pair<Long, String>?>(null)

    





    private fun claim(generation: Long, id: String): Boolean {
        if (generation != this.generation) return false
        val candidate = generation to id
        val after = lastReported.updateAndGet { current ->
            
            
            
            if (current != null && (current.first > generation || current == candidate)) current else candidate
        }
        
        return after === candidate
    }

    @Volatile
    private var scope: CoroutineScope? = null

    
    
    
    
    
    @Volatile
    private var replaying = false

    
    @Volatile
    private var generation = 0L

    
    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    
    fun onConversationOpened(forWhat: For) {
        if (!replaying) return
        val scope = scope ?: return
        val startGeneration = generation
        scope.launch { report(forWhat, startGeneration) }
    }

    




    fun onReplayStarted() {
        generation++
        replaying = true
        val startGeneration = generation
        val scope = scope ?: return
        scope.launch {
            
            val id = withContext(Dispatchers.Main.immediate) { SendMessageUtils.currentChat() } ?: return@launch
            report(resolveConversation(id), startGeneration)
        }
    }

    
    fun onQueueEmpty() {
        replaying = false
    }

    private suspend fun report(forWhat: For, startGeneration: Long) {
        
        
        
        
        if (!claim(startGeneration, forWhat.id)) return

        val isGroup = forWhat is For.Group
        val body = if (isGroup) {
            SetPriorConversationRequestBody(gid = forWhat.id)
        } else {
            SetPriorConversationRequestBody(number = forWhat.id)
        }
        try {
            val response = httpClient.get().httpService.setPriorConversation(
                body
            )
            if (response.status == 0) {
                L.i { "[PriorConversation] sent gen=$startGeneration type=${if (isGroup) "group" else "account"} id=${forWhat.id}" }
            } else {
                L.w { "[PriorConversation] failed gen=$startGeneration id=${forWhat.id} status=${response.status} reason=${response.reason}" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[PriorConversation] failed gen=$startGeneration id=${forWhat.id} err=${e.stackTraceToString()}" }
        }
    }

    




    private fun resolveConversation(id: String): For =
        if (id.startsWith("+")) For.Account(id) else For.Group(id)
}
