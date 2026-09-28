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

/**
 * Reports the conversation the user is currently viewing to the server while an offline backlog is
 * being replayed, so that conversation's messages are delivered first (parity with iOS
 * `DTConversationPreviewManager` and Desktop `conversationToFront`).
 *
 * Best-effort by design: one attempt, no retry, failures only logged. The server only uses the
 * hint to order backlog delivery, so a lost call costs nothing but the optimisation, and the
 * replay window itself is seconds long.
 */
@Singleton
class PriorConversationReporter @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val httpClient: Lazy<ChativeHttpClient>,
) {
    // Last reported (replay generation, conversation id). The server keeps only the latest hint,
    // so switching back to a conversation must report it again (iOS/Desktop do the same); only an
    // immediate repeat of the same conversation is skipped. Claimed atomically so the Main-thread
    // onResume caller and the IO replay-start caller cannot both send the same report.
    private val lastReported = AtomicReference<Pair<Long, String>?>(null)

    /**
     * Returns true if this call won the right to report [id] for [generation]. Two stale-window
     * guards, both needed: the first read rejects a report whose window was superseded before it
     * got here; the CAS arm rejects one whose window is superseded by a claim landing between that
     * read and the CAS.
     */
    private fun claim(generation: Long, id: String): Boolean {
        if (generation != this.generation) return false
        val candidate = generation to id
        val after = lastReported.updateAndGet { current ->
            // Keep the current value when it is an equal report, or belongs to a newer window
            // (generation is monotonic; a caller that read its generation before a newer window
            // opened must not overwrite the live window's state). Otherwise take the candidate.
            if (current != null && (current.first > generation || current == candidate)) current else candidate
        }
        // Identity, not equality: only the winning CAS installs this exact instance.
        return after === candidate
    }

    @Volatile
    private var scope: CoroutineScope? = null

    // Replay window of the current connection: opened by `queue/conversation/empty`, closed by
    // `queue/empty` or by the observer stopping. Equivalent of iOS `needReportConversation`, but
    // per connection rather than per process because a resident mobile app reconnects many times.
    // A connection that drops between the two markers leaves the window open until the next
    // connection's own marker re-opens it; a hint sent in that gap is harmless (iOS behaves the same).
    @Volatile
    private var replaying = false

    // Written only by the WebSocket read loop (single coroutine), read from Main and IO.
    @Volatile
    private var generation = 0L

    /** Called once from `IncomingMessageObserver.start()`; supplies the scope the POST runs in. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Chat screen resumed. Only reports while a replay is actually in progress. */
    fun onConversationOpened(forWhat: For) {
        if (!replaying) return
        val scope = scope ?: return
        val startGeneration = generation
        scope.launch { report(forWhat, startGeneration) }
    }

    /**
     * `queue/conversation/empty` was read: the connection is about to replay its backlog. Also
     * covers "the user was already sitting in a chat when the network came back", which on mobile
     * is the common case.
     */
    fun onReplayStarted() {
        generation++
        replaying = true
        val startGeneration = generation
        val scope = scope ?: return
        scope.launch {
            // currentChatList is mutated on Main by the chat screens' onResume/onPause.
            val id = withContext(Dispatchers.Main.immediate) { SendMessageUtils.currentChat() } ?: return@launch
            report(resolveConversation(id), startGeneration)
        }
    }

    /** `queue/empty` was read: this connection's backlog is over, later opens report nothing. */
    fun onQueueEmpty() {
        replaying = false
    }

    private suspend fun report(forWhat: For, startGeneration: Long) {
        // The window gates where a report is *started*, not whether a started one completes: on a
        // small backlog `queue/empty` follows `queue/conversation/empty` within ~100ms, so re-checking
        // `replaying` here would suppress the one report that matters (the chat the user was in when
        // the network came back), and a hint landing after the backlog only costs the optimisation.
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

    /**
     * The open-chat registry stores plain ids. Account ids are numbers (`+…`), everything else is a
     * group id whatever its shape — the same rule the rest of the receive path uses. Synchronous on
     * purpose: a DB lookup here would cost more than the whole replay window on a small backlog.
     */
    private fun resolveConversation(id: String): For =
        if (id.startsWith("+")) For.Account(id) else For.Group(id)
}
