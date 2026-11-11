package com.difft.android.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.utils.appScope
import com.difft.android.messageserialization.db.store.DraftRepository
import difft.android.messageserialization.model.Draft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class DraftViewModel @Inject constructor(
    private val draftRepository: DraftRepository
) : ViewModel() {

    
    private class Update(val roomId: String, val draft: Draft, val version: Long)

    private val writer = DraftWriter(draftRepository)

    
    private val draftUpdateFlow = MutableSharedFlow<Update>(extraBufferCapacity = 1)

    
    private val pending = AtomicReference<Update?>(null)

    init {
        draftUpdateFlow
            .debounce(500L) 
            .onEach { update ->
                pending.compareAndSet(update, null)
                writer.upsert(update.roomId, update.draft, update.version)
            }
            .flowOn(Dispatchers.IO).launchIn(viewModelScope)
    }

    


    suspend fun loadDraft(roomId: String): Draft? = withContext(Dispatchers.IO) {
        draftRepository.getDraft(roomId)
    }

    


    fun updateDraft(roomId: String, draft: Draft) {
        val update = Update(roomId, draft, writer.nextVersion())
        pending.set(update)
        
        draftUpdateFlow.tryEmit(update)
    }

    





    fun flushNow() {
        val update = pending.getAndSet(null) ?: return
        appScope.launch { writer.upsert(update.roomId, update.draft, update.version) }
    }

    


    fun clearDraft(roomId: String) {
        pending.updateAndGet { if (it?.roomId == roomId) null else it }
        val version = writer.nextVersion()
        viewModelScope.launch(Dispatchers.IO) {
            writer.clear(roomId, version)
        }
    }
}
