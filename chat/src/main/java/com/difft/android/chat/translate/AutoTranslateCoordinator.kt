package com.difft.android.chat.translate

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.ChatMessageListBehavior
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.chat.translate.model.DownloadTrigger
import com.difft.android.chat.translate.model.TranslateModelStore
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import difft.android.messageserialization.For
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.TranslateTargetLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.difft.app.database.hydration.MessageHydrator
import org.difft.app.database.models.MessageModel
import java.util.concurrent.ConcurrentHashMap

/** Why a visible range is being reported. Decides nothing here; kept for the diagnostics line. */
enum class VisibleReason { FIRST_SCREEN, SCROLL_IDLE, WINDOW_CHANGED }

/**
 * Owns auto-translate for ONE conversation page. Created by `ChatMessageViewModel`, torn down with
 * it.
 *
 * Delivery is driven by ONE long-lived collector on [TranslateQueue.completions], not by whoever
 * happens to be awaiting a handle. Every result for this conversation lands in the overlay (and,
 * when the state is persistable, in the database) whether it was awaited or not — so giving up on a
 * wait, re-reporting a visible range or scrolling away never loses a translation.
 *
 * "Never loses" is scoped to the page's lifetime: a completion that resolves after [close] is
 * dropped, so re-entering the conversation re-translates those messages. Only results already
 * computed by then are drained and written.
 *
 * [overlay] is authoritative over hydration for the page's lifetime: a window re-query in flight
 * when a translation is written would otherwise hand back the pre-write value.
 */
class AutoTranslateCoordinator @AssistedInject constructor(
    @Assisted private val forWhat: For,
    @Assisted private val scope: CoroutineScope,
    private val queue: TranslateQueue,
    private val engine: TranslateEngine,
    private val modelStore: TranslateModelStore,
    private val backend: TranslateBackend,
    private val dbMessageStore: DBMessageStore,
    private val dbRoomStore: DBRoomStore,
    private val conversationSettingsManager: ConversationSettingsManager,
    private val messageHydrator: MessageHydrator,
) {

    private val _overlay = MutableStateFlow<Map<String, TranslateData>>(emptyMap())

    /** messageId -> the translation state the page must render, whatever the database says. */
    val overlay: StateFlow<Map<String, TranslateData>> = _overlay.asStateFlow()

    private val ownerKey = forWhat.id
    private val manualOwnerKey = "${forWhat.id}#manual"

    /** "$messageId#$target", claimed with `add()` so admission is a single atomic step. */
    private val attempted = ConcurrentHashMap.newKeySet<String>()
    private val userDisabled = ConcurrentHashMap.newKeySet<String>()

    /** Target language of the last manual request per message, so an inline retry can repeat it. */
    private val manualTargets = ConcurrentHashMap<String, TranslateTargetLanguage>()

    /** The current window, kept so a visible-range report can resolve ids to bodies. */
    private val windowMessages = ConcurrentHashMap<String, TextChatMessage>()

    @Volatile
    private var target: TranslateLang? = null

    /** False until the stored value has been read, so the first apply is never skipped as a no-op. */
    @Volatile
    private var settingApplied = false

    @Volatile
    private var lastWindowIds: Set<String> = emptySet()

    @Volatile
    private var lastWindowMaxTs = Long.MIN_VALUE

    @Volatile
    private var lastVisibleIds: List<String> = emptyList()

    private val pendingResults = mutableMapOf<String, TranslateData>()

    /**
     * Attempt key (message + target) -> a gate completed once that attempt's result has been handled
     * by the completions collector. Keyed by attempt, not by message: after a target switch a
     * leftover old-target completion must not open the wait for the new target.
     * A budget wait has to wait for DELIVERY, not for the queue handle: the handle is
     * resolved by the worker BEFORE the collector has written anything, so joining it can return to
     * an empty batch and push the translation out to the next 800 ms flush — the very jump the
     * budget exists to prevent.
     */
    private val deliveries = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /**
     * Guards [pendingResults] and [flushJob]. A plain monitor rather than a `Mutex` because
     * [close] — which is not a suspending function — has to drain the batch before the page's scope
     * goes away; nothing inside the critical sections suspends.
     */
    private val batchLock = Any()
    private var flushJob: Job? = null

    /**
     * Set by [close]. It suppresses ALL post-close completion handling, not just the flush timer: a
     * result arriving afterwards is discarded outright — no overlay update, no batch entry, no
     * write — because the page it was computed for is gone.
     */
    @Volatile
    private var closed = false

    /**
     * The queue's session epoch as of this page. Re-read immediately before every write that runs on
     * [appScope]; see [persistIfCurrent].
     */
    private val startGeneration = queue.generation

    fun start() {
        queue.completions
            .filter { it.owner == ownerKey || it.owner == manualOwnerKey }
            .onEach { onCompletion(it) }
            .launchIn(scope)

        scope.launch(Dispatchers.IO) {
            val stored = runCatching { dbRoomStore.getTranslateSetting(forWhat.id) }.getOrDefault(0)
            applySetting(stored)
        }

        conversationSettingsManager.conversationSettingUpdate
            .filter { it.conversationId == forWhat.id && it.translateSetting != null }
            .onEach { applySetting(it.translateSetting ?: 0) }
            .launchIn(scope)

        // A model finishing its download is the moment the current viewport becomes translatable.
        modelStore.state
            .map { target?.let { t -> modelStore.isReady(t) } ?: false }
            .distinctUntilChanged()
            .filter { it }
            .onEach { onVisibleRange(lastVisibleIds, VisibleReason.FIRST_SCREEN) }
            .launchIn(scope)
    }

    /**
     * Scenario A: a bounded wait so a brand-new incoming message and its translation reach the list
     * in the same frame. Returns immediately when the setting is off, the model is missing or the
     * emission carries no new candidate, and never fails — a missed budget is picked up by the
     * batch channel.
     */
    suspend fun awaitNewIncoming(behavior: ChatMessageListBehavior) {
        val lang = target ?: return
        if (!modelStore.isReady(lang)) return
        val fresh = selectNewIncoming(behavior)
        if (fresh.isEmpty()) return

        val myId = globalServices.myId
        val inputs = withContext(Dispatchers.IO) {
            val hydration = runCatching { messageHydrator.hydrate(fresh) }.getOrNull() ?: return@withContext emptyList()
            fresh.mapNotNull { model ->
                model.toTranslateCandidateInput(hydration[model.id], myId)
                    ?.let { toCandidate(it, lang, attempted, userDisabled) }
            }
        }
        if (inputs.isEmpty()) return

        val startedAt = System.currentTimeMillis()
        val waits = inputs.map { submitAuto(it, lang, TranslateQueue.Priority.NEW_INCOMING) }
        val hit = withTimeoutOrNull(NEW_INCOMING_BUDGET_MS) { waits.forEach { it.await() } } != null
        flush()
        L.i {
            "[Translate] budget room=${forWhat.id} n=${inputs.size} hit=$hit " +
                "cost=${System.currentTimeMillis() - startedAt}ms"
        }
    }

    /**
     * Records the window that was just assembled. Pure bookkeeping — the overlay is already a
     * combine source, so nothing is applied here.
     */
    fun onWindowAssembled(messages: List<ChatMessage>) {
        val ids = HashSet<String>(messages.size)
        var maxTs = Long.MIN_VALUE
        messages.forEach { message ->
            ids.add(message.id)
            if (message.systemShowTimestamp > maxTs) maxTs = message.systemShowTimestamp
            if (message is TextChatMessage) windowMessages[message.id] = message
        }
        windowMessages.keys.retainAll(ids)
        lastWindowIds = ids
        if (maxTs > lastWindowMaxTs) lastWindowMaxTs = maxTs
    }

    /** Scenarios B and C: translate what the user is actually looking at. */
    fun onVisibleRange(messageIds: List<String>, reason: VisibleReason) {
        lastVisibleIds = messageIds
        val lang = target ?: return
        if (messageIds.isEmpty() || !modelStore.isReady(lang)) return
        scope.launch {
            val inputs = messageIds.mapNotNull { id ->
                windowMessages[id]?.let { toCandidate(it.toTranslateCandidateInput(), lang, attempted, userDisabled) }
            }
            if (inputs.isEmpty()) return@launch
            L.i { "[Translate] visible room=${forWhat.id} reason=$reason n=${inputs.size}" }
            // Deliberately NOT cancelling anything still in flight for ids that are still visible:
            // the queue's key dedup merges them, and cancelling a wait would only lose the batching.
            val waits = inputs.map { submitAuto(it, lang, TranslateQueue.Priority.VISIBLE) }
            withTimeoutOrNull(VISIBLE_BATCH_BUDGET_MS) { waits.forEach { it.await() } }
            flush()
        }
    }

    /** Long-press "translate": an explicit user action, so it gets a placeholder and error states. */
    fun translateManually(message: TextChatMessage, targetLanguage: TranslateTargetLanguage) {
        val lang = if (targetLanguage == TranslateTargetLanguage.ZH) TranslateLang.ZH else TranslateLang.EN
        val text = manualBody(message)
        if (text.isNullOrBlank()) return
        val previous = currentData(message.id) ?: EMPTY_TRANSLATE_DATA
        manualTargets[message.id] = targetLanguage
        userDisabled.remove(message.id)
        _overlay.update { it + (message.id to previous.copy(translateStatus = TranslateStatus.Translating)) }

        val handle = queue.submit(
            owner = manualOwnerKey,
            key = "${message.id}#${lang.name}#manual",
            messageId = message.id,
            priority = TranslateQueue.Priority.NEW_INCOMING,
            text = text,
            target = lang,
            mode = TranslateMode.MANUAL,
        )
        scope.launch {
            try {
                handle.await()
            } catch (e: CancellationException) {
                // The queue cancels a pending handle when this page goes away; roll the optimistic
                // placeholder back so a surviving observer never sees a frozen spinner.
                _overlay.update { it + (message.id to previous) }
                throw e
            }
        }
    }

    /** Long-press "turn translation off": hide it and remember the choice for this page session. */
    fun translateOff(message: TextChatMessage) {
        val current = currentData(message.id) ?: return
        val off = current.copy(translateStatus = TranslateStatus.Invisible)
        _overlay.update { it + (message.id to off) }
        userDisabled.add(message.id)
        persistOne(message.id, off)
    }

    /** Inline retry on a failed manual translation. */
    fun retry(messageId: String) {
        val message = windowMessages[messageId] ?: return
        val targetLanguage = manualTargets[messageId] ?: return
        translateManually(message, targetLanguage)
    }

    /**
     * Teardown, called from `onCleared()` — i.e. AFTER AndroidX has already cancelled the page's
     * scope. Whatever is still waiting for the 800 ms batch timer is therefore drained here and
     * written on [appScope]: the overlay is irrelevant once the page is gone, but a translation that
     * was already computed must not be thrown away — it would be recomputed, and paid for again in
     * backend time, on the next visit.
     */
    fun close() {
        closed = true
        queue.cancelOwner(ownerKey)
        queue.cancelOwner(manualOwnerKey)
        val leftover = synchronized(batchLock) {
            flushJob?.cancel()
            flushJob = null
            pendingResults.filterValues { it.translateStatus.isPersistable }
                .also { pendingResults.clear() }
        }
        windowMessages.clear()
        releaseDeliveries()
        if (leftover.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) { persistBatch(leftover) }
        }
        L.i { "[Translate] coordinator closed room=${forWhat.id} drained=${leftover.size}" }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Submits and hands back the message's delivery gate — see [deliveries]. The queue handle is
     * left to the queue: it is what [TranslateQueue.cancelOwner] cancels, and nothing here has to
     * observe that.
     */
    private fun submitAuto(
        input: TranslateJobInput,
        lang: TranslateLang,
        priority: TranslateQueue.Priority,
    ): CompletableDeferred<Unit> {
        val key = attemptKey(input.messageId, lang)
        val delivery = deliveries.computeIfAbsent(key) { CompletableDeferred() }
        queue.submit(
            owner = ownerKey,
            key = key,
            messageId = input.messageId,
            priority = priority,
            text = input.text,
            target = lang,
            mode = TranslateMode.AUTO,
        )
        return delivery
    }

    private fun completeDelivery(attemptKey: String) {
        deliveries.remove(attemptKey)?.complete(Unit)
    }

    /** Frees every waiter whose job has just been dropped, so no wait outlives its own work. */
    private fun releaseDeliveries() {
        deliveries.values.forEach { it.complete(Unit) }
        deliveries.clear()
    }

    private fun selectNewIncoming(behavior: ChatMessageListBehavior): List<MessageModel> {
        val window = behavior.messageList
        if (window.isEmpty()) return emptyList()
        if (lastWindowIds.isEmpty()) {
            // First screen while sitting at the bottom deserves the same one-shot treatment as a
            // live message: take the newest few instead of waiting for the visible-range pass.
            return window.sortedBy { it.systemShowTimestamp }.takeLast(FIRST_SCREEN_MAX)
        }
        return window.filter { it.id !in lastWindowIds && it.systemShowTimestamp > lastWindowMaxTs }
    }

    /**
     * A build with no translate backend reads every stored setting as off: the row stays in the
     * database untouched, so the conversation translates again as soon as it runs on a build that
     * can.
     */
    private suspend fun applySetting(value: Int) {
        val newTarget = if (backend.isSupported) TranslateLang.fromSetting(value) else null
        if (settingApplied && newTarget == target) return
        settingApplied = true
        target = newTarget
        attempted.clear()
        // Only the automatic owner: a manual translation the user is waiting for must survive a
        // setting change.
        queue.cancelOwner(ownerKey)
        releaseDeliveries()
        val ready = newTarget != null && modelStore.isReady(newTarget)
        L.i { "[Translate] coordinator start room=${forWhat.id} target=${newTarget?.name} modelReady=$ready" }
        val lang = newTarget ?: return
        modelStore.ensureDownloaded(lang, DownloadTrigger.ENTER_CONVERSATION)
        if (ready) scope.launch(Dispatchers.IO) { runCatching { engine.warmUp(lang) } }
        onVisibleRange(lastVisibleIds, VisibleReason.FIRST_SCREEN)
    }

    private suspend fun onCompletion(completion: TranslateQueue.Completion) {
        val isManual = completion.owner == manualOwnerKey
        try {
            deliver(completion, isManual)
        } finally {
            // AFTER the result has reached the pending batch, and on every early return above it —
            // stale target, released claim, closed page — so a budget wait is never left hanging.
            if (!isManual) completeDelivery(completion.key)
        }
    }

    private suspend fun deliver(completion: TranslateQueue.Completion, isManual: Boolean) {
        if (closed) return
        // A result for a target the conversation has since moved away from is stale; a manual
        // result is what the user explicitly asked for, so it is never filtered.
        if (!isManual && completion.target != target) return

        val data = mapResult(completion, isManual)
        if (data == null) {
            if (!isManual && completion.result is TranslateResult.ModelNotReady) {
                // Release the claim: the model-ready subscription will sweep this message again.
                attempted.remove(completion.key)
            }
            return
        }

        if (isManual) {
            _overlay.update { it + (completion.messageId to data) }
            if (completion.result is TranslateResult.ModelNotReady) {
                modelStore.ensureDownloaded(completion.target, DownloadTrigger.MANUAL_TRANSLATE)
            }
            persistOne(completion.messageId, data)
            return
        }

        synchronized(batchLock) {
            if (closed) return
            pendingResults[completion.messageId] = data
            if (flushJob?.isActive != true) {
                flushJob = scope.launch {
                    delay(FLUSH_DELAY_MS)
                    flush()
                }
            }
        }
    }

    /**
     * AUTO renders successes only: a silent skip is the whole point of an automatic mode, and an
     * inline "translation failed" row under every message of a screenful would be noise the user
     * never asked for. MANUAL is an explicit action and therefore reports every outcome.
     */
    private fun mapResult(completion: TranslateQueue.Completion, isManual: Boolean): TranslateData? {
        val previous = currentData(completion.messageId) ?: EMPTY_TRANSLATE_DATA
        return when (val result = completion.result) {
            is TranslateResult.Translated -> when (completion.target) {
                TranslateLang.ZH -> previous.copy(
                    translateStatus = TranslateStatus.ShowCN,
                    translatedContentCN = result.text,
                )

                TranslateLang.EN -> previous.copy(
                    translateStatus = TranslateStatus.ShowEN,
                    translatedContentEN = result.text,
                )
            }

            TranslateResult.SameLanguage ->
                if (isManual) previous.copy(translateStatus = TranslateStatus.SameLanguage) else null

            TranslateResult.ModelNotReady ->
                if (isManual) previous.copy(translateStatus = TranslateStatus.Failed) else null

            is TranslateResult.Unsupported -> when {
                !isManual -> null
                result.reason == TranslateResult.Reason.TOO_LONG ->
                    previous.copy(translateStatus = TranslateStatus.FailedTooLong)

                else -> previous.copy(translateStatus = TranslateStatus.Failed)
            }

            is TranslateResult.Failed -> {
                L.w {
                    "[Translate] failed room=${forWhat.id} msg=${completion.messageId} " +
                        "reason=${result.cause.javaClass.simpleName}"
                }
                if (isManual) previous.copy(translateStatus = TranslateStatus.Failed) else null
            }
        }
    }

    private suspend fun flush() {
        val batch = synchronized(batchLock) {
            if (pendingResults.isEmpty()) return
            pendingResults.toMap().also { pendingResults.clear() }
        }
        _overlay.update { it + batch }
        val persistable = batch.filterValues { it.translateStatus.isPersistable }
        L.i {
            "[Translate] batch room=${forWhat.id} applied=${batch.size} persisted=${persistable.size} " +
                "skippedByStatus=${batch.size - persistable.size}"
        }
        if (persistable.isEmpty()) return
        // [appScope], not [scope]: the results are already rendered, so a page teardown racing this
        // launch would only lose the row and pay for the same translation again on the next visit.
        appScope.launch(Dispatchers.IO) { persistBatch(persistable) }
    }

    /**
     * Deliberately no `RoomChangeTracker.trackRoom` here: it re-runs the conversation page's most
     * expensive query, and every incoming message would trigger it twice.
     */
    private suspend fun persistBatch(batch: Map<String, TranslateData>) =
        persistIfCurrent("n=${batch.size}") {
            dbMessageStore.updateMessageTranslateDataBatch(forWhat.id, batch)
        }

    /**
     * A user action's write must survive leaving the page — otherwise "turn translation off"
     * followed by a back press silently comes back on the next visit. [appScope], not [scope]:
     * AndroidX cancels the ViewModel's scope BEFORE `onCleared()` runs [close], so a write launched
     * on the page's own scope in the same gesture would never start.
     */
    private fun persistOne(messageId: String, data: TranslateData) {
        if (!data.translateStatus.isPersistable) return
        appScope.launch(Dispatchers.IO) {
            persistIfCurrent("msg=$messageId") {
                dbMessageStore.updateMessageTranslateData(forWhat.id, messageId, data)
            }
        }
    }

    /**
     * The single guard for every translate write. [NonCancellable] because the results are already
     * in the overlay, so losing the row to a page teardown would silently re-translate the same
     * messages on the next visit.
     *
     * Running on a scope that outlives the page also means outliving a logout that is wiping the
     * database, so the queue's epoch is re-read HERE, inside the uncancellable block, rather than at
     * launch time: a bump since [startGeneration] means the account this translation belongs to is
     * gone and the row must not be written.
     */
    private suspend fun persistIfCurrent(what: String, write: suspend () -> Unit) =
        withContext(NonCancellable) {
            if (queue.generation != startGeneration) {
                L.i { "[Translate] persist skipped room=${forWhat.id} $what reason=sessionEnded" }
                return@withContext
            }
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[Translate] persist failed room=${forWhat.id} $what: ${e.stackTraceToString()}" }
            }
        }

    private fun currentData(messageId: String): TranslateData? =
        _overlay.value[messageId] ?: windowMessages[messageId]?.translateData

    private fun manualBody(message: TextChatMessage): String? {
        val forwards = message.forwardContext?.forwards
        return if (forwards?.size == 1) forwards.first().text else message.message?.toString()
    }

    private companion object {
        const val NEW_INCOMING_BUDGET_MS = 400L
        const val VISIBLE_BATCH_BUDGET_MS = 800L
        const val FLUSH_DELAY_MS = 800L
        const val FIRST_SCREEN_MAX = 10
        val EMPTY_TRANSLATE_DATA = TranslateData(TranslateStatus.Invisible, null, null)
    }
}

@AssistedFactory
interface AutoTranslateCoordinatorFactory {
    fun create(forWhat: For, scope: CoroutineScope): AutoTranslateCoordinator
}
