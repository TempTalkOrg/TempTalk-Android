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
import com.difft.android.chat.translate.model.ModelState
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


enum class VisibleReason { FIRST_SCREEN, SCROLL_IDLE, WINDOW_CHANGED }

















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

    
    val overlay: StateFlow<Map<String, TranslateData>> = _overlay.asStateFlow()

    private val ownerKey = forWhat.id
    private val manualOwnerKey = "${forWhat.id}#manual"

    
    private val attempted = ConcurrentHashMap.newKeySet<String>()
    private val userDisabled = ConcurrentHashMap.newKeySet<String>()

    
    private val manualTargets = ConcurrentHashMap<String, TranslateTargetLanguage>()

    
    private val manualAttempt = ConcurrentHashMap<String, Any>()

    private val pendingManual = PendingManualTranslations()

    
    private val windowMessages = ConcurrentHashMap<String, TextChatMessage>()

    @Volatile
    private var target: TranslateLang? = null

    
    @Volatile
    private var settingApplied = false

    @Volatile
    private var lastWindowIds: Set<String> = emptySet()

    @Volatile
    private var lastWindowMaxTs = Long.MIN_VALUE

    @Volatile
    private var lastVisibleIds: List<String> = emptyList()

    private val pendingResults = mutableMapOf<String, TranslateData>()

    








    private val deliveries = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    




    private val batchLock = Any()
    private var flushJob: Job? = null

    




    @Volatile
    private var closed = false

    



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

        
        modelStore.state
            .map { target?.let { t -> modelStore.isReady(t) } ?: false }
            .distinctUntilChanged()
            .filter { it }
            .onEach { onVisibleRange(lastVisibleIds, VisibleReason.FIRST_SCREEN) }
            .launchIn(scope)

        
        modelStore.state
            .onEach { resumePendingManual(it) }
            .launchIn(scope)
    }

    




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
            
            
            val waits = inputs.map { submitAuto(it, lang, TranslateQueue.Priority.VISIBLE) }
            withTimeoutOrNull(VISIBLE_BATCH_BUDGET_MS) { waits.forEach { it.await() } }
            flush()
        }
    }

    
    fun translateManually(message: TextChatMessage, targetLanguage: TranslateTargetLanguage) {
        val lang = if (targetLanguage == TranslateTargetLanguage.ZH) TranslateLang.ZH else TranslateLang.EN
        val text = manualBody(message)
        if (text.isNullOrBlank()) return
        val previous = currentData(message.id) ?: EMPTY_TRANSLATE_DATA
        manualTargets[message.id] = targetLanguage
        pendingManual.remove(message.id)
        userDisabled.remove(message.id)
        _overlay.update { it + (message.id to previous.copy(translateStatus = TranslateStatus.Translating)) }
        val attempt = Any().also { manualAttempt[message.id] = it }

        scope.launch {
            
            
            fun stillWanted() = !closed && message.id !in userDisabled && manualAttempt[message.id] === attempt
            val failedModel = previous.copy(translateStatus = TranslateStatus.FailedModel)
            try {
                
                
                val ready = modelStore.awaitReady(lang)
                if (!stillWanted()) return@launch
                
                
                val result = if (ready) {
                    submitWaitingForPacks(modelStore, ::stillWanted) {
                        queue.submit(
                            owner = manualOwnerKey,
                            key = "${message.id}#${lang.name}#manual",
                            messageId = message.id,
                            priority = TranslateQueue.Priority.NEW_INCOMING,
                            text = text,
                            target = lang,
                            mode = TranslateMode.MANUAL,
                        )
                    }
                } else {
                    TranslateResult.ModelNotReady(emptySet())
                }
                if (result is TranslateResult.ModelNotReady && stillWanted()) {
                    pendingManual.record(message, targetLanguage, modelStore.requiredModels(lang) + result.missing)
                    _overlay.update { it + (message.id to failedModel) }
                }
            } catch (e: CancellationException) {
                
                
                if (manualAttempt[message.id] === attempt) _overlay.update { it + (message.id to previous) }
                throw e
            } catch (e: Exception) {
                L.e { "[Translate] manual failed room=${forWhat.id} msg=${message.id}: ${e.stackTraceToString()}" }
                if (stillWanted()) _overlay.update { it + (message.id to failedModel) }
            }
        }
    }

    
    fun translateOff(message: TextChatMessage) {
        val current = currentData(message.id) ?: return
        val off = current.copy(translateStatus = TranslateStatus.Invisible)
        _overlay.update { it + (message.id to off) }
        userDisabled.add(message.id)
        pendingManual.remove(message.id)
        persistOne(message.id, off)
    }

    
    fun retry(messageId: String) {
        val message = windowMessages[messageId] ?: return
        val targetLanguage = manualTargets[messageId] ?: return
        translateManually(message, targetLanguage)
    }

    






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
        pendingManual.clear()
        releaseDeliveries()
        if (leftover.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) { persistBatch(leftover) }
        }
        L.i { "[Translate] coordinator closed room=${forWhat.id} drained=${leftover.size}" }
    }

    

    




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

    
    private fun releaseDeliveries() {
        deliveries.values.forEach { it.complete(Unit) }
        deliveries.clear()
    }

    
    private fun resumePendingManual(models: Map<ModelId, ModelState>) {
        if (closed) return
        pendingManual.takeReady { models[it] == ModelState.Ready }
            .filterNot { it.message.id in userDisabled }
            .forEach {
                L.i { "[Translate] manual resume room=${forWhat.id} msg=${it.message.id}" }
                translateManually(it.message, it.targetLanguage)
            }
    }

    private fun selectNewIncoming(behavior: ChatMessageListBehavior): List<MessageModel> {
        val window = behavior.messageList
        if (window.isEmpty()) return emptyList()
        if (lastWindowIds.isEmpty()) {
            
            
            return window.sortedBy { it.systemShowTimestamp }.takeLast(FIRST_SCREEN_MAX)
        }
        return window.filter { it.id !in lastWindowIds && it.systemShowTimestamp > lastWindowMaxTs }
    }

    




    private suspend fun applySetting(value: Int) {
        val newTarget = if (backend.isSupported) TranslateLang.fromSetting(value) else null
        if (settingApplied && newTarget == target) return
        settingApplied = true
        target = newTarget
        attempted.clear()
        
        
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
            
            
            if (!isManual) completeDelivery(completion.key)
        }
    }

    private suspend fun deliver(completion: TranslateQueue.Completion, isManual: Boolean) {
        if (closed) return
        
        
        if (!isManual && completion.target != target) return
        
        if (isManual && completion.result is TranslateResult.ModelNotReady) return

        val data = mapResult(completion, isManual)
        if (data == null) {
            if (completion.result is TranslateResult.ModelNotReady) {
                
                attempted.remove(completion.key)
            }
            return
        }

        if (isManual) {
            
            if (completion.messageId in userDisabled) return
            pendingManual.remove(completion.messageId)
            _overlay.update { it + (completion.messageId to data) }
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

            
            is TranslateResult.ModelNotReady -> null

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
        
        
        appScope.launch(Dispatchers.IO) { persistBatch(persistable) }
    }

    



    private suspend fun persistBatch(batch: Map<String, TranslateData>) =
        persistIfCurrent("n=${batch.size}") {
            dbMessageStore.updateMessageTranslateDataBatch(forWhat.id, batch)
        }

    





    private fun persistOne(messageId: String, data: TranslateData) {
        if (!data.translateStatus.isPersistable) return
        appScope.launch(Dispatchers.IO) {
            persistIfCurrent("msg=$messageId") {
                dbMessageStore.updateMessageTranslateData(forWhat.id, messageId, data)
            }
        }
    }

    









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
