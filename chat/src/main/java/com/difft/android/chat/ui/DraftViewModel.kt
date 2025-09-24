package com.difft.android.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class DraftViewModel @Inject constructor(
    private val draftRepository: DraftRepository
) : ViewModel() {

    // Use a SharedFlow to collect draft update requests.
    private val draftUpdateFlow = MutableSharedFlow<Pair<String, Draft>>(extraBufferCapacity = 1)

    init {
        draftUpdateFlow
            .debounce(500L) // Debounce the updates to avoid saving too frequently.
            .onEach { (roomId, draft) ->
                draftRepository.upsertDraft(roomId, draft)
            }
            .flowOn(Dispatchers.IO).launchIn(viewModelScope)
    }

    /**
     * Load the draft for a room.
     */
    suspend fun loadDraft(roomId: String): Draft? = withContext(Dispatchers.IO) {
        draftRepository.getDraft(roomId)
    }

    /**
     * Instead of updating the draft immediately, emit the update to our debounced flow.
     */
    fun updateDraft(roomId: String, draft: Draft) {
        // Try to emit the latest draft update.
        draftUpdateFlow.tryEmit(roomId to draft)
    }

    /**
     * Clear the draft.
     */
    fun clearDraft(roomId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            draftRepository.clearDraft(roomId)
        }
    }
}