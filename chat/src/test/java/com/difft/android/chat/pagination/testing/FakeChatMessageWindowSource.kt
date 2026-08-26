package com.difft.android.chat.pagination.testing

import com.difft.android.chat.pagination.ChatMessageWindowSource
import com.difft.android.chat.pagination.RoomAnchors
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.difft.app.database.models.MessageModel

/**
 * In-memory [ChatMessageWindowSource]. Every method matches its interface KDoc literally —
 * boundary operator, ordering and LIMIT-after-ORDER — because those semantics are the only thing
 * standing between the controller cases and a silent false green. Method order here mirrors the
 * interface so the two can be diffed by eye against the migration table.
 *
 * Precondition: seeded `systemShowTimestamp` values must be pairwise distinct. SQLite does not
 * define the order of ties, so a fake cannot reproduce it and cases must avoid that domain.
 *
 * Thread safety: the controller calls this from `withContext(Dispatchers.IO)` — a real pool — so
 * state is guarded by a lock and every read returns an immutable snapshot.
 */
class FakeChatMessageWindowSource(
    initial: List<MessageModel> = emptyList(),
    /** Backing value for [roomAnchors]; null models "the room has no row". */
    var roomRow: RoomAnchors? = RoomAnchors(readPosition = 0L, sendStatus = ROOM_SEND_STATUS_NONE),
) : ChatMessageWindowSource {

    private val lock = Any()
    private var messages: List<MessageModel> = initial.sortedBy { it.systemShowTimestamp }
    private val calls = mutableListOf<Call>()
    private val changeSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    /** One recorded call: the interface method plus its scalar arguments. */
    data class Call(val method: String, val args: List<Any?>)

    // --- driving surface (tests only) ---

    fun seed(messages: List<MessageModel>) = synchronized(lock) {
        this.messages = messages.sortedBy { it.systemShowTimestamp }
    }

    /** Appends rows at the newest end and fires one change signal (a new message landing in DB). */
    suspend fun appendAndNotify(vararg newMessages: MessageModel) {
        synchronized(lock) { messages = (messages + newMessages).sortedBy { it.systemShowTimestamp } }
        notifyChange()
    }

    /**
     * Inserts rows OLDER than the current window and fires one change signal — models a
     * cross-batch out-of-order websocket delivery or a failed message re-inserted at its original
     * timestamp. The CRIT-1 entry point: such a row is invisible to the observer's window query,
     * so only a re-run COUNT can notice it.
     */
    suspend fun insertOlderAndNotify(vararg olderMessages: MessageModel) =
        appendAndNotify(*olderMessages)

    /** Fires a change signal without touching data (a receipt / status update re-query). */
    suspend fun notifyChange() {
        // The controller subscribes from a flowOn(IO) coroutine, i.e. asynchronously with respect
        // to the call that started the observer. Waiting for the subscription is what keeps the
        // signal from being dropped by a replay-less SharedFlow.
        changeSignals.subscriptionCount.first { it > 0 }
        changeSignals.emit(Unit)
    }

    // --- observation surface (assertions) ---

    val callLog: List<Call> get() = synchronized(lock) { calls.toList() }

    fun callCount(method: String): Int = synchronized(lock) { calls.count { it.method == method } }

    /** Live collector count of [messageChanges] — the observer-restart guard reads this. */
    val activeChangeCollectors: Int get() = changeSignals.subscriptionCount.value

    // --- ChatMessageWindowSource ---

    override fun roomAnchors(): RoomAnchors? = record("roomAnchors") { roomRow }

    override fun earliestFailedOutgoing(): MessageModel? = record("earliestFailedOutgoing") {
        messages.firstOrNull {
            it.sendType == MessageModel.SEND_TYPE_FAILED &&
                it.type != MessageModel.TYPE_NOTIFY &&
                it.type != MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER
        }
    }

    override fun firstUnreadFromOthers(readPosition: Long, myId: String): MessageModel? =
        record("firstUnreadFromOthers", readPosition, myId) {
            messages.firstOrNull { it.systemShowTimestamp > readPosition && it.fromWho != myId }
        }

    override fun countOlderThan(ts: Long): Int =
        record("countOlderThan", ts) { messages.count { it.systemShowTimestamp < ts } }

    override fun countNewerThan(ts: Long): Int =
        record("countNewerThan", ts) { messages.count { it.systemShowTimestamp > ts } }

    override fun newerThan(ts: Long, limit: Long): List<MessageModel> =
        record("newerThan", ts, limit) { ascending { it > ts }.take(limit) }

    override fun atOrNewerThan(ts: Long, limit: Long): List<MessageModel> =
        record("atOrNewerThan", ts, limit) { ascending { it >= ts }.take(limit) }

    override fun olderThan(ts: Long, limit: Long): List<MessageModel> =
        record("olderThan", ts, limit) { descending { it < ts }.take(limit) }

    override fun atOrOlderThan(ts: Long, limit: Long): List<MessageModel> =
        record("atOrOlderThan", ts, limit) { descending { it <= ts }.take(limit) }

    override fun latest(limit: Long): List<MessageModel> =
        record("latest", limit) { descending { true }.take(limit) }

    override fun latestMessageId(): String? =
        record("latestMessageId") { messages.lastOrNull()?.id }

    override fun byTimeStamp(timeStamp: Long): MessageModel? =
        record("byTimeStamp", timeStamp) { messages.firstOrNull { it.timeStamp == timeStamp } }

    override fun ascendingFrom(fromTs: Long, toTs: Long?): List<MessageModel> =
        record("ascendingFrom", fromTs, toTs) {
            ascending { ts -> ts >= fromTs && (toTs == null || ts <= toTs) }
        }

    override val messageChanges: Flow<Unit> = changeSignals

    // --- internals ---

    private fun ascending(predicate: (Long) -> Boolean): List<MessageModel> =
        messages.filter { predicate(it.systemShowTimestamp) }

    private fun descending(predicate: (Long) -> Boolean): List<MessageModel> =
        messages.filter { predicate(it.systemShowTimestamp) }.asReversed()

    /** SQL LIMIT semantics: applied AFTER ordering. */
    private fun List<MessageModel>.take(limit: Long): List<MessageModel> = take(limit.toInt())

    private fun <T> record(method: String, vararg args: Any?, body: () -> T): T =
        synchronized(lock) {
            calls += Call(method, args.toList())
            body()
        }
}
