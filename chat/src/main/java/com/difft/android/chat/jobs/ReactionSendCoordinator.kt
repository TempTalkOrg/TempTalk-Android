package com.difft.android.chat.jobs

import androidx.annotation.VisibleForTesting
import com.difft.android.PushReactionSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.google.gson.Gson
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.mapToMessageId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton













@Singleton
class ReactionSendCoordinator @Inject constructor(
    private val gson: Gson,
    private val dataSerializer: Data.Serializer,
    @param:Named(REACTION_COORDINATOR_SCOPE) private val scope: CoroutineScope,
) {
    @VisibleForTesting
    internal val mutexes = ConcurrentHashMap<String, Mutex>()

    fun enqueueReactionWithDedupe(
        conversationId: String,
        realMessageId: String,
        reaction: Reaction,
        textMessage: TextMessage,
        factory: PushReactionSendJobFactory,
    ) {
        scope.launch {
            
            
            
            val mutex = mutexes.computeIfAbsent(conversationId) { Mutex() }
            val jobManager = ApplicationDependencies.getJobManager()
            mutex.withLock {
                val queueKey = "[${PushReactionSendJob.KEY}::$conversationId]"
                val duplicates = jobManager.findJobsInQueue(queueKey).filter {
                    matchesReactionKey(it, realMessageId, reaction.emoji, reaction.uid)
                }
                duplicates.forEach { spec ->
                    L.i {
                        "[Reaction][Coordinator] superseding pending job=${spec.id} " +
                                "target=$conversationId emoji=${reaction.emoji} " +
                                "uid=${reaction.uid} ts=${reaction.originTimestamp}"
                    }
                    jobManager.cancel(spec.id)
                }
                jobManager.add(factory.create(null, textMessage))
            }
        }
    }

    
    
    
    
    private fun matchesReactionKey(
        spec: JobSpec,
        realMessageId: String,
        emoji: String,
        uid: String,
    ): Boolean {
        if (spec.factoryKey != PushReactionSendJob.KEY) return false

        return try {
            val data: Data = dataSerializer.deserialize(spec.serializedData)
            val textMessageJson = data.getString(PushReactionSendJob.KEY_MESSAGE_OUT) ?: return false
            val textMessage: TextMessage = gson.fromJson(textMessageJson, TextMessage::class.java)
                ?: return false
            val candidateReaction = textMessage.reactions?.firstOrNull() ?: return false
            val candidateRealMessageId = candidateReaction.realSource?.mapToMessageId()?.idValue
                ?: return false

            candidateRealMessageId == realMessageId &&
                    candidateReaction.emoji == emoji &&
                    candidateReaction.uid == uid
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            L.w {
                "[Reaction][Coordinator] matcher failed spec=${spec.id} " +
                        "err=${e.javaClass.simpleName}: ${e.stackTraceToString()}"
            }
            false
        }
    }

    companion object {
        const val REACTION_COORDINATOR_SCOPE = "reaction-coordinator-scope"
    }
}
