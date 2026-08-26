package com.difft.android.chat

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.qualifier.User
import com.difft.android.chat.pagination.ChatMessageWindowSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.difft.app.database.models.MessageModel

// care of  ConversationUtils.messagesUpdate.filter { it == forWhat.id }
// All [source] calls in this class are blocking and run on Dispatchers.IO.
class ChatNormalPaginationController @AssistedInject constructor(
    @Assisted
    private val forWhat: For,
    // The whole reason this is an interface and not a WCDB: constructing any winq Expression loads
    // a native library that the host JVM has no .so for, which used to force every test on this
    // class to @Ignore. Keep this type free of `com.tencent.wcdb.*`.
    @Assisted
    private val source: ChatMessageWindowSource,
    // Comparison base for "from others". Same source as `isMine` (`Record2MessageFactory` compares
    // `globalServices.myId` with `record.fromWho`) so "from others" has ONE meaning. `UserInfoModule`
    // falls back to "" when no user data is loaded; a blank id only degrades the anchor decision
    // towards today's behavior, it cannot crash or mis-place the divider.
    @param:User.Uid private val myId: String,
) : BaseChatPaginationController() {
    companion object {
        /** First screen of a conversation: smallest of the three, it is on the first-frame path. */
        const val INITIAL_PAGE_SIZE: Long = 20L

        /**
         * One scroll-driven page. Larger than the first screen because what costs while scrolling is
         * the number of round trips, not the rows per trip.
         */
        const val SCROLL_PAGE_SIZE: Long = 50L

        /**
         * One jump landing (`jumpToMessage` / `jumpToBottom`). Same value as [INITIAL_PAGE_SIZE]
         * today but a separate symbol: it is tuned for landing latency, not scroll throughput, and
         * the two must be able to move apart.
         */
        const val JUMP_PAGE_SIZE: Long = 20L

        /**
         * Cap on the loaded window. Independent of every page size — it is bounded by adapter/diff
         * and hydration cost, not by how many rows one load fetches.
         */
        const val MAX_MESSAGE_COUNT = 180

        /**
         * Rows one [trimToLatest] reclaims. Derived, never a second literal: re-tuning
         * [MAX_MESSAGE_COUNT] must move the whole hysteresis band with it.
         */
        const val TRIM_SLACK = MAX_MESSAGE_COUNT / 2

        /**
         * Window size at which the Fragment starts asking for a trim. The band
         * `[MAX_MESSAGE_COUNT, TRIM_HIGH_WATER]` is the hysteresis that keeps a conversation parked
         * at the bottom from trimming once per incoming message — one trim per [TRIM_SLACK]
         * messages instead.
         */
        const val TRIM_HIGH_WATER = MAX_MESSAGE_COUNT + TRIM_SLACK

        private const val NANOS_PER_MILLI = 1_000_000L
    }

    private suspend fun hasOlderMessages(beforeTimestamp: Long): Boolean =
        source.countOlderThan(beforeTimestamp) != 0

    /**
     * True when there is no message older than [messageList]'s oldest entry. NOT derivable for
     * free from `MessageWindow.anchorBefore` — `anchorBefore == null` is ambiguous on the
     * read-position-anchored first-screen path (it can mean "we didn't look", not "there is
     * nothing before"). Empty [messageList] returns true (empty/new conversation: header alone,
     * intentional low-risk default).
     */
    private suspend fun computeHasReachedHistoryStart(messageList: List<MessageModel>): Boolean {
        val oldestTimestamp = messageList.minOfOrNull { it.systemShowTimestamp } ?: return true
        return !hasOlderMessages(oldestTimestamp)
    }

    override suspend fun initLoadMessage(jumpMessageTimeStamp: Long?) = withContext(Dispatchers.IO) {
        if (jumpMessageTimeStamp != null && jumpMessageTimeStamp > 0L) {
            jumpToMessage(jumpMessageTimeStamp)
            Unit
        } else {
            loadNormalChatDefaultMessages()
        }
    }

    /**
     * Entry point for opening a conversation without a jump target. Owns the anchoring decision:
     * the gate lives here and NOT in [initLoadMessage], so the `jumpToMessage` entry keeps its
     * behavior untouched.
     */
    private suspend fun loadNormalChatDefaultMessages() {
        // One row, two columns — replaces a single-column readPosition read, so the gate below adds
        // ZERO queries for the overwhelming majority of rooms (sendStatus == NONE).
        val roomAnchors = source.roomAnchors()
        val readPosition = roomAnchors?.readPosition ?: 0L
        val roomSendStatus = roomAnchors?.sendStatus ?: ROOM_SEND_STATUS_NONE

        // `== FAILED`, not `!= NONE`: enabling the future SENDING aggregate must not start moving
        // the first screen.
        if (roomSendStatus == ROOM_SEND_STATUS_FAILED) {
            val failed = source.earliestFailedOutgoing()
            if (failed != null) {
                // Must be this dedicated query, not expectedUnreadMessages — see
                // decideFirstScreenAnchor's KDoc for why the latter disables the anchoring.
                val firstUnreadOthersTs = source.firstUnreadFromOthers(readPosition, myId)
                    ?.systemShowTimestamp
                val anchor = decideFirstScreenAnchor(
                    firstFailedTs = failed.systemShowTimestamp,
                    firstUnreadOthersTs = firstUnreadOthersTs,
                )
                L.i {
                    "[${forWhat.id}] first screen anchor decision: failedTs=${failed.systemShowTimestamp} " +
                        "firstUnreadOthersTs=$firstUnreadOthersTs -> $anchor"
                }
                if (anchor is FirstScreenAnchor.AtFailedMessage) {
                    loadFirstScreenAnchoredAtFailure(failed, readPosition)
                    return
                }
            }
        }
        loadFirstScreenFromReadPosition(readPosition)
    }

    /** Default first screen: window built around [readPosition], divider rule untouched. */
    private suspend fun loadFirstScreenFromReadPosition(readPosition: Long) {
        // 多查询一条未读消息用作后锚点
        val expectedUnreadMessages = source.newerThan(readPosition, INITIAL_PAGE_SIZE + 1)
        L.i { "[${forWhat.id}] Load normal chat default messages, expectedUnreadMessages.size = ${expectedUnreadMessages.size}" }

        val allMessages = if (expectedUnreadMessages.size < INITIAL_PAGE_SIZE) {
            // 如果未读消息不够一页，补充已读消息，多查一条用作前锚点
            val expectedMessages = source.atOrOlderThan(
                readPosition, INITIAL_PAGE_SIZE - expectedUnreadMessages.size + 1
            )
            L.i { "[${forWhat.id}] Load normal chat default messages, expectedReadMessages.size = ${expectedMessages.size}" }
            expectedMessages + expectedUnreadMessages
        } else {
            expectedUnreadMessages
        }

        val sortedMessages = allMessages.sortedBy { it.systemShowTimestamp }
        L.i { "[${forWhat.id}] Load normal chat default messages, sortedMessages.size = ${sortedMessages.size}" }

        // 拆分锚点消息和显示消息
        val window = splitMessageWindow(sortedMessages, expectedUnreadMessages.size, INITIAL_PAGE_SIZE.toInt())
        val pageMessages = window.pageMessages

        // 计算初始滚动位置
        val scrollToPosition = if (expectedUnreadMessages.isNotEmpty()) {
            val firstUnreadInPage = expectedUnreadMessages.firstOrNull { it in pageMessages }
            if (firstUnreadInPage != null) {
                pageMessages.indexOfFirst { it.id == firstUnreadInPage.id }
            } else if (pageMessages.isNotEmpty()) {
                pageMessages.size - 1
            } else {
                -1
            }
        } else if (pageMessages.isNotEmpty()) {
            pageMessages.size - 1
        } else {
            -1
        }

        _chatMessagesStateFlow.value = ChatMessageListBehavior(
            messageList = pageMessages,
            scrollAction = if (scrollToPosition >= 0) ScrollAction.ToPosition(scrollToPosition) else null,
            updateTimestamp = System.currentTimeMillis(),
            anchorMessageBefore = window.anchorBefore,
            anchorMessageAfter = window.anchorAfter,
            readPosition = readPosition,
            hasReachedHistoryStart = computeHasReachedHistoryStart(pageMessages),
            // Over-fetch verdict, zero extra queries: the `gt` query asked for one row more than a
            // page, so a short result means every row newer than readPosition was loaded — and the
            // back-fill branch only ever drops rows off the OLDEST end, so they are all in the
            // window.
            hasReachedLatest = expectedUnreadMessages.size <= INITIAL_PAGE_SIZE,
        )
        observerMessagesChanges()
    }

    /**
     * First screen anchored at [failed] — the earliest thing the user has not dealt with.
     *
     * [readPosition] is passed through unchanged: the divider is a session-scoped anchor in
     * `ChatMessageViewModel`, so it renders if and only if its boundary message is in the loaded
     * window, and it survives later pages. No suppression is needed here — the window only ever
     * moves EARLIER than the default one, so the real first unread is either inside it (correct
     * divider) or past its end (no candidate, nothing drawn).
     *
     * Window shape mirrors [jumpToMessage] but is keyed on `systemShowTimestamp` — the display-order
     * column the whole controller pages on. `timeStamp` (the local clock at compose time) is used
     * ONLY as the `ScrollAction.ToMessage` key and MUST NOT be used to build the window: on a failed
     * message the two can disagree, which would desync the window from its own ordering.
     */
    private suspend fun loadFirstScreenAnchoredAtFailure(
        failed: MessageModel,
        readPosition: Long,
    ) {
        val anchorTs = failed.systemShowTimestamp
        // Query one extra message to use as the after-anchor.
        val afterMessages = source.atOrNewerThan(anchorTs, INITIAL_PAGE_SIZE + 1)
        val allMessages = if (afterMessages.size < INITIAL_PAGE_SIZE) {
            // Less than a full page after the anchor: back-fill earlier messages, one extra for the before-anchor.
            val earlierMessages = source.olderThan(anchorTs, INITIAL_PAGE_SIZE - afterMessages.size + 1)
            earlierMessages + afterMessages
        } else {
            afterMessages
        }

        val sortedMessages = allMessages.sortedBy { it.systemShowTimestamp }

        // Split into anchor messages and the page to display.
        val window = splitMessageWindow(sortedMessages, afterMessages.size, INITIAL_PAGE_SIZE.toInt())

        L.i {
            "[${forWhat.id}] first screen anchored at failed message: anchorTs=$anchorTs " +
                "page=${window.pageMessages.size}"
        }

        _chatMessagesStateFlow.value = ChatMessageListBehavior(
            messageList = window.pageMessages,
            // ToMessage, not ToPosition: index-free (the Fragment resolves it against the
            // transformed list, so it is immune to the mapNotNull/filterNot drift a raw page index
            // suffers) and exempt from the call-header scroll compensation, which only skips
            // ToMessage and would otherwise yank the anchored view back to the bottom.
            scrollAction = ScrollAction.ToMessage(failed.timeStamp),
            updateTimestamp = System.currentTimeMillis(),
            anchorMessageBefore = window.anchorBefore,
            anchorMessageAfter = window.anchorAfter,
            readPosition = readPosition,
            hasReachedHistoryStart = computeHasReachedHistoryStart(window.pageMessages),
            // Same over-fetch verdict as the read-position path, on the `ge` query this one uses.
            hasReachedLatest = afterMessages.size <= INITIAL_PAGE_SIZE,
        )
        observerMessagesChanges()
    }

    override
    suspend fun loadPreviousPage(): Boolean = withContext(Dispatchers.IO) {// true indicates done loading data, false indicates still has messages left
        val current = chatMessagesStateFlow.value
        val currentMessages = current?.messageList ?: emptyList()
        val oldestMessageSystemShowTimeStamp: Long =
            currentMessages.minOfOrNull { it.systemShowTimestamp } ?: Long.MAX_VALUE

        // 多查询一条用作前锚点
        val allPageMessages = source.olderThan(oldestMessageSystemShowTimeStamp, SCROLL_PAGE_SIZE + 1)
        L.i { "[${forWhat.id}] loadPreviousPage, allPageMessages: ${allPageMessages.size}" }

        // 拆分锚点消息和要显示的消息
        val anchorMessageBefore = if (allPageMessages.size > SCROLL_PAGE_SIZE.toInt()) allPageMessages.last() else null
        val pageMessages = if (allPageMessages.size > SCROLL_PAGE_SIZE.toInt()) {
            allPageMessages.dropLast(1)
        } else {
            allPageMessages
        }

        val messageList = (pageMessages + currentMessages).distinctBy { it.id }.sortedBy { it.systemShowTimestamp }
        val newMessageList = messageList.take(MAX_MESSAGE_COUNT)

        // 如果消息列表被截断，使用被截断的第一条作为后锚点
        val anchorMessageAfter = if (messageList.size > MAX_MESSAGE_COUNT) {
            messageList[MAX_MESSAGE_COUNT]
        } else null

        // Hoisted above the ChatMessageListBehavior construction so its result feeds BOTH the new
        // hasReachedHistoryStart field and this function's return value — one query, not two.
        // NOTE: `hasOlder` true means MORE messages remain below the loaded window — callers keep
        // paging while it is true. It is NOT a "done loading" flag; inverting the polarity here
        // silently kills history paging.
        val displayMinSystemShowTimestamp =
            newMessageList.minOfOrNull { it.systemShowTimestamp } ?: Long.MIN_VALUE
        val hasOlder = hasOlderMessages(displayMinSystemShowTimestamp)

        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(
                messageList = newMessageList,
                scrollAction = ScrollAction.PreservePosition, // 加载上一页不滚动，保持当前位置
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = anchorMessageBefore,
                anchorMessageAfter = anchorMessageAfter,
                hasReachedHistoryStart = !hasOlder,
                // AND, never a plain carry-forward. This is the ONE emission site that drops rows
                // off the NEWEST end (`take(MAX_MESSAGE_COUNT)` keeps the OLDEST N), and a
                // carried-forward `true` would then claim "nothing newer exists" about rows it just
                // threw away — permanently suppressing loadNextPage, so scrolling back down after a
                // few pages up would never load again. anchorMessageAfter != null IS that
                // truncation signal, computed above at zero query cost.
                hasReachedLatest = anchorMessageAfter == null && (current?.hasReachedLatest ?: false),
            )
        observerMessagesChanges()
        return@withContext hasOlder
    }

    override
    suspend fun loadNextPage(): Boolean = withContext(Dispatchers.IO) { // true indicates done loading data, false indicates still has messages left
        val currentMessages = chatMessagesStateFlow.value?.messageList ?: emptyList()
        val latestMessageSystemShowTimeStamp: Long =
            currentMessages.maxOfOrNull { it.systemShowTimestamp }
                ?: Long.MIN_VALUE

        // 多查询一条用作后锚点
        val allPageMessages = source.newerThan(latestMessageSystemShowTimeStamp, SCROLL_PAGE_SIZE + 1)

        // 拆分锚点消息和要显示的消息
        val anchorMessageAfter = if (allPageMessages.size > SCROLL_PAGE_SIZE.toInt()) allPageMessages.last() else null
        val pageMessages = if (allPageMessages.size > SCROLL_PAGE_SIZE.toInt()) {
            allPageMessages.dropLast(1)
        } else {
            allPageMessages
        }

        val allMessages = (currentMessages + pageMessages).distinctBy { it.id }.sortedBy { it.systemShowTimestamp }
        // 如果消息列表被截断，使用被截断的最后一条作为前锚点
        val latestWindow = takeLatestWindow(allMessages, MAX_MESSAGE_COUNT)
        val messageList = latestWindow.pageMessages
        val anchorMessageBefore = latestWindow.droppedNeighbour
        L.i { "[${forWhat.id}] loadNextPage, after mering exist messages and new messages and take max size of messages, messageList: ${messageList.size}" }

        // Read here, before the emission, so this one query drives both this return value and
        // hasReachedLatest below. An empty window skips the query and returns false.
        val displayMaxSystemShowTimestamp = messageList.maxOfOrNull { it.systemShowTimestamp }
        val hasNewer = displayMaxSystemShowTimestamp != null &&
            source.countNewerThan(displayMaxSystemShowTimestamp) != 0

        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(
                messageList = messageList,
                scrollAction = ScrollAction.PreservePosition, // 加载下一页不滚动，保持当前位置
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = anchorMessageBefore,
                anchorMessageAfter = anchorMessageAfter,
                hasReachedHistoryStart = computeHasReachedHistoryStart(messageList),
                // Empty window keeps the non-suppressing default rather than claiming the newest end.
                hasReachedLatest = displayMaxSystemShowTimestamp != null && !hasNewer,
            )
        observerMessagesChanges()
        return@withContext hasNewer
    }

    override
    suspend fun jumpToMessage(messageTimeStamp: Long): Boolean = withContext(Dispatchers.IO) {
        //start from the message with the given id, and load the next 40 messages include it
        val targetMessage = source.byTimeStamp(messageTimeStamp)
        if (targetMessage == null) {
            return@withContext false
        } else {
            // 多查询一条用作后锚点
            val afterMessages =
                source.atOrNewerThan(targetMessage.systemShowTimestamp, JUMP_PAGE_SIZE + 1)
            L.i { "[${forWhat.id}] jumpToMessage, afterMessages behind with current message: ${afterMessages.size}" }

            val allMessages = if (afterMessages.size < JUMP_PAGE_SIZE) {
                //if the afterMessages is less than pageSize, then load the previous messages to make up the page
                // 多查询一条用作前锚点
                val expectedMessages = source.olderThan(
                    targetMessage.systemShowTimestamp, JUMP_PAGE_SIZE - afterMessages.size + 1
                )
                expectedMessages + afterMessages
            } else {
                afterMessages
            }
            L.i { "[${forWhat.id}] jumpToMessage, after load previous messages, allMessages: ${allMessages.size}" }

            val sortedMessages = allMessages.sortedBy { it.systemShowTimestamp }

            // 拆分锚点消息和显示消息
            val window = splitMessageWindow(sortedMessages, afterMessages.size, JUMP_PAGE_SIZE.toInt())

            L.i { "[${forWhat.id}] jumpToMessage, after make up hot data and convert from message Model, pageMessages: ${window.pageMessages.size}" }

            _chatMessagesStateFlow.value =
                ChatMessageListBehavior(
                    messageList = window.pageMessages,
                    scrollAction = ScrollAction.ToMessage(messageTimeStamp), // 滚动到目标消息
                    updateTimestamp = System.currentTimeMillis(),
                    anchorMessageBefore = window.anchorBefore,
                    anchorMessageAfter = window.anchorAfter,
                    hasReachedHistoryStart = computeHasReachedHistoryStart(window.pageMessages),
                    // Over-fetch verdict on the `ge` query above; zero extra queries.
                    hasReachedLatest = afterMessages.size <= JUMP_PAGE_SIZE,
                )
            observerMessagesChanges()
        }
        return@withContext true
    }

    override
    suspend fun jumpToBottom() = withContext(Dispatchers.IO) {
        // 多查询一条用作前锚点
        val allMessages = source.latest(JUMP_PAGE_SIZE + 1)

        val sortedMessages = allMessages.distinctBy { it.id }
            .sortedBy { it.systemShowTimestamp }
        L.i { "[${forWhat.id}] jumpToBottom, after convert from message Model, sortedMessages: ${sortedMessages.size}" }

        // 拆分锚点消息和显示消息（跳到底部不需要后锚点）
        val anchorMessageBefore = if (sortedMessages.size > JUMP_PAGE_SIZE.toInt()) sortedMessages.first() else null
        val pageMessages = if (sortedMessages.size > JUMP_PAGE_SIZE.toInt()) {
            sortedMessages.drop(1)
        } else {
            sortedMessages
        }

        _chatMessagesStateFlow.value =
            ChatMessageListBehavior(
                messageList = pageMessages,
                scrollAction = ScrollAction.ToBottom, // 滚动到底部
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = anchorMessageBefore,
                hasReachedHistoryStart = computeHasReachedHistoryStart(pageMessages),
                // The window is built from the newest rows down, so by construction it terminates
                // at the conversation's newest message.
                hasReachedLatest = true,
            )
        observerMessagesChanges()
    }


    private var observeMessageChangesJob: Job? = null

    private suspend fun observerMessagesChanges() {
        observeMessageChangesJob?.cancelAndJoin()
        val lastMessageId = source.latestMessageId()
        val currentMessageList = chatMessagesStateFlow.value?.messageList ?: emptyList()
        val existMessageIds = currentMessageList.map { it.id }.toTypedArray()
        val minSystemShowTimeStamp =
            currentMessageList.minOfOrNull { it.systemShowTimestamp }
                ?: Long.MIN_VALUE
        val maxSystemShowTimeStamp =
            currentMessageList.maxOfOrNull { it.systemShowTimestamp }
                ?: Long.MAX_VALUE
        // null upper bound == the unbounded `ge(min)` branch, which keeps absorbing newer rows.
        val windowUpperBound: Long? = if (lastMessageId == null || lastMessageId in existMessageIds) {
            L.i { "[${forWhat.id}] observerMessagesChanges, include new incoming messages" }
            null
        } else {
            L.i { "[${forWhat.id}] observerMessagesChanges, not include new incoming messages" }
            maxSystemShowTimeStamp
        }
        observeMessageChangesJob = source.messageChanges
            .onEach {
                // 获取新消息列表
                // Timed on purpose: this unbounded window re-query is the single costliest query on
                // the conversation screen, and the query layer behind it is deliberately unlogged.
                val startedNs = System.nanoTime()
                val updatedMessages = source.ascendingFrom(minSystemShowTimeStamp, windowUpperBound)
                val queryCostMs = (System.nanoTime() - startedNs) / NANOS_PER_MILLI
                L.i {
                    "[message] observer window re-query room=${forWhat.id} " +
                        "rows=${updatedMessages.size} cost=${queryCostMs}ms"
                }
                // Anchors are RECOMPUTED here, not carried forward: this re-query can move either
                // edge of the window, and an emission without anchors makes the first row lose its
                // day header / name and the last row lose its time.
                val edges = if (updatedMessages.isEmpty()) {
                    WindowEdges(null, null)
                } else {
                    source.resolveWindowEdges(
                        oldestTs = updatedMessages.first().systemShowTimestamp,
                        newestTs = updatedMessages.last().systemShowTimestamp,
                    )
                }
                // scrollAction = null，让 Fragment 根据 isAtBottom 自己判断是否滚动
                _chatMessagesStateFlow.value = ChatMessageListBehavior(
                    messageList = updatedMessages,
                    scrollAction = null,
                    updateTimestamp = System.currentTimeMillis(),
                    anchorMessageBefore = edges.anchorBefore,
                    anchorMessageAfter = edges.anchorAfter,
                    hasReachedHistoryStart = computeHasReachedHistoryStart(updatedMessages),
                    // Free: the after-anchor probe already answered "does a newer row exist".
                    // The isNotEmpty() guard matters on the frozen (`between`) branch: if every row
                    // of the window is deleted while newer rows exist outside it, no probe ran, and
                    // claiming the newest end would let the bottom gate suppress the one page load
                    // that could refill the screen.
                    hasReachedLatest = updatedMessages.isNotEmpty() && edges.anchorAfter == null,
                )
            }
            .flowOn(Dispatchers.IO)
            .launchIn(coroutineScope)
    }

    override fun addOneMessage(messageModel: MessageModel) {
        // Atomic read-modify-write: this runs on Dispatchers.IO and shares the flow with the
        // observer's writer, so update{} avoids the lost-update window a .value read-then-set has.
        _chatMessagesStateFlow.update { current ->
            val currentMessages = current?.messageList ?: emptyList()
            // Skip if the observer re-query already surfaced it, to avoid a duplicate list bubble.
            if (currentMessages.any { it.id == messageModel.id }) return@update current
            ChatMessageListBehavior(
                messageList = currentMessages + messageModel,
                scrollAction = ScrollAction.ToBottom, // 发送消息后滚动到底部
                updateTimestamp = System.currentTimeMillis(),
                // Appending at the newest end cannot change the oldest displayed row, so the
                // before-anchor stays valid — carry it forward. Zero queries.
                anchorMessageBefore = current?.anchorMessageBefore,
                // The appended row IS the newest known one; a carried-forward after-anchor would be
                // OLDER than it and would corrupt the last row's showTime decision. null == "no
                // known neighbour". Do NOT "complete" this by symmetry with the line above.
                anchorMessageAfter = null,
                // No query: appending at the newest end can never change whether the oldest
                // loaded message is the true first — carry the existing signal forward.
                hasReachedHistoryStart = current?.hasReachedHistoryStart ?: false,
                // Same for the newest end: the append happened AT that end, nothing was dropped.
                hasReachedLatest = current?.hasReachedLatest ?: false,
            )
        }
    }

    /**
     * Drops the oldest rows so the window is back at [MAX_MESSAGE_COUNT]. Pure in-memory re-slice —
     * no DB query. The caller MUST have established that the viewport is at the bottom; this
     * function has no viewport knowledge of its own.
     *
     * CRIT-2 INVARIANT — the re-slice must land in the SAME main-loop turn as the caller's
     * `isAtBottom` check, so no scroll can happen in between. Two conditions carry it, BOTH
     * load-bearing:
     *  1. the caller launches this from a `Main.immediate` context (`viewLifecycleOwner
     *     .lifecycleScope`), so `launch { trimToLatest() }` starts executing synchronously rather
     *     than being posted;
     *  2. NO SUSPENSION POINT may execute before the [_chatMessagesStateFlow] `update {}` below —
     *     any suspending call, IO hop, suspending log sink, mutex acquisition or `yield()` inserted
     *     above it hands the main loop back, lets a scroll/layout land, and reopens exactly the race
     *     the caller's `isAtBottom` gate was supposed to close.
     *
     * Wrapping the re-slice in `withContext(Dispatchers.IO)` is the loudest way to break (2) — and
     * tempting, since every other method here does it — but it is only one instance of it. Review
     * this function as "nothing may suspend first", not as "do not use IO". Only the observer
     * restart, strictly AFTER the update, hops to IO.
     */
    override suspend fun trimToLatest() {
        var sizeBefore = 0
        var sizeAfter = 0
        _chatMessagesStateFlow.update { current ->
            if (current == null || current.messageList.size <= MAX_MESSAGE_COUNT) return@update current
            val window = takeLatestWindow(current.messageList, MAX_MESSAGE_COUNT)
            sizeBefore = current.messageList.size
            sizeAfter = window.pageMessages.size
            current.copy(
                messageList = window.pageMessages,
                // Not null: the null branch auto-snaps to the bottom and fires a read receipt, and a
                // trim changes no content the user has not already seen.
                scrollAction = ScrollAction.PreservePosition,
                updateTimestamp = System.currentTimeMillis(),
                anchorMessageBefore = window.droppedNeighbour,
                // Rows were dropped off the OLDEST end, so older rows provably exist — no COUNT
                // needed. The newest end is untouched, so anchorMessageAfter / hasReachedLatest
                // carry forward via copy().
                hasReachedHistoryStart = false,
            )
        }
        // sizeBefore is only ever written on the trimming path, where it is > MAX_MESSAGE_COUNT, so
        // 0 means "no trim happened" — nothing was emitted and there is nothing to restart.
        if (sizeBefore == 0) return
        L.i { "[${forWhat.id}] trimToLatest: $sizeBefore -> $sizeAfter (highWater=$TRIM_HIGH_WATER)" }
        // Half the feature, not a coda: without the restart the next change signal still re-queries
        // from the OLD window minimum and the window snaps straight back to its pre-trim size.
        withContext(Dispatchers.IO) { observerMessagesChanges() }
    }
}
