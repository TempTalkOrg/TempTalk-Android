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

    /** A draft with the version it was produced at (see [DraftWriter]). */
    private class Update(val roomId: String, val draft: Draft, val version: Long)

    private val writer = DraftWriter(draftRepository)

    // Use a SharedFlow to collect draft update requests.
    private val draftUpdateFlow = MutableSharedFlow<Update>(extraBufferCapacity = 1)

    // Latest update not yet written; flushNow() writes it without waiting for the debounce.
    private val pending = AtomicReference<Update?>(null)

    init {
        draftUpdateFlow
            .debounce(500L) // Debounce the updates to avoid saving too frequently.
            .onEach { update ->
                pending.compareAndSet(update, null)
                writer.upsert(update.roomId, update.draft, update.version)
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
        val update = Update(roomId, draft, writer.nextVersion())
        pending.set(update)
        // Try to emit the latest draft update.
        draftUpdateFlow.tryEmit(update)
    }

    /**
     * Writes the latest pending draft now, bypassing the debounce: called when the input pauses
     * (fold/unfold recreation, page covered or popped) so a draft typed just before survives. Runs
     * in the app scope because this ViewModel may be cleared right after; [DraftWriter] keeps it
     * ordered with the debounced save.
     */
    fun flushNow() {
        val update = pending.getAndSet(null) ?: return
        appScope.launch { writer.upsert(update.roomId, update.draft, update.version) }
    }

    /**
     * Clear the draft.
     */
    fun clearDraft(roomId: String) {
        pending.updateAndGet { if (it?.roomId == roomId) null else it }
        val version = writer.nextVersion()
        viewModelScope.launch(Dispatchers.IO) {
            writer.clear(roomId, version)
        }
    }
}
