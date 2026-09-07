package com.difft.android.chat.contacts.contactsdetail.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.common.upload.ContactAvatarUploader
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactRemarkEditContract.Effect
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactRemarkEditContract.Intent
import com.difft.android.chat.contacts.contactsdetail.appendQuickFill
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactRemarkEditContract.State
import com.difft.android.chat.contacts.contactsdetail.shouldOfferQuickFill
import com.difft.android.chat.contacts.contactsremark.ContactRemarkUtil
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.ConversationSetRequestBody
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.models.ContactorModel

/**
 * Remark name / remark avatar editing for the contact card. Wire format is unchanged from the
 * retired remark sub-page: `POST v1/conversation/set` with `remark` / `remarkAvatar`, each
 * client-encrypted as `"V1|" + base64(iv ‖ AES-GCM(uid-derived key))`; an empty string clears.
 */
@HiltViewModel
class ContactRemarkEditViewModel @Inject constructor(
    private val contactAvatarUploader: ContactAvatarUploader,
    @ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.Load -> load(intent.contactor)
            Intent.EnterEdit -> enterEdit()
            is Intent.ChangeName -> changeName(intent.value)
            Intent.SubmitName -> submitName()
            Intent.DiscardEdit -> exitEdit()
            Intent.AvatarClick -> onAvatarClick()
            is Intent.AvatarPicked -> uploadAvatar(intent.path)
            Intent.RestoreAvatar -> setRemarkAvatar(encryptedValue = "", uploadedPath = null)
            Intent.QuickFillName -> quickFill()
            Intent.RequestClose -> requestClose()
            Intent.DiscardAndClose -> {
                exitEdit()
                _effect.trySend(Effect.Close)
            }

            Intent.SaveAndClose -> submitName(closeAfter = true)
        }
    }

    /** The input opens on the stored remark only — the real name is reachable through quick-fill. */
    private fun enterEdit() {
        _state.update {
            it.copy(
                isEditing = true,
                editBaseline = it.remarkName,
                editingName = it.remarkName,
                // Decided once here: later typing must not make the row flicker in and out.
                showQuickFill = shouldOfferQuickFill(it.quickFillName, it.remarkName),
            ).withDerived()
        }
    }

    /**
     * Using the shortcut retires it for the rest of this edit, even when it had nothing to append.
     * The result is capped at [MAX_REMARK_LENGTH] the same way the input caps typing and pasting —
     * the shortcut writes the draft directly, so the field's own filter never sees it.
     */
    private fun quickFill() {
        _state.update {
            it.copy(
                editingName = appendQuickFill(it.editingName, it.quickFillName).take(MAX_REMARK_LENGTH),
                showQuickFill = false,
            ).withDerived()
        }
    }

    private fun changeName(value: String) {
        _state.update { it.copy(editingName = value).withDerived() }
    }

    /** Close / back while editing: ask only when the user actually changed the text away from the baseline. */
    private fun requestClose() {
        val s = _state.value
        if (s.hasUnsavedChanges) {
            _effect.trySend(Effect.ShowUnsavedDialog)
            return
        }
        if (s.isEditing) exitEdit()
        _effect.trySend(Effect.Close)
    }

    private fun State.withDerived(): State =
        copy(hasUnsavedChanges = isEditing && editingName.trim() != editBaseline.trim())

    /** Edit actions need a loaded contact; the header can be tapped before the first [Intent.Load] lands. */
    private fun loadedContactId(action: String): String? {
        val id = _state.value.contactId
        if (id.isEmpty()) {
            L.w { "[ContactRemarkEdit] $action ignored: contact not loaded yet" }
            _effect.trySend(Effect.ShowToast(null))
            return null
        }
        return id
    }

    private fun load(contactor: ContactorModel) {
        val remark = ContactRemarkCache.getRemark(contactor.id)?.takeIf { it.isNotEmpty() }
            ?: contactor.remark?.takeIf { it.isNotEmpty() }
            ?: contactor.groupMemberContactor?.remark?.takeIf { it.isNotEmpty() }
            ?: ""
        val remarkAvatar = ContactRemarkCache.getRemarkAvatar(contactor.id)?.takeIf { it.isNotEmpty() }
            ?: contactor.remarkAvatar?.takeIf { it.isNotEmpty() }
            ?: contactor.groupMemberContactor?.remarkAvatar?.takeIf { it.isNotEmpty() }
        // Quick-fill source: public name → name → group display name. The base58 id fallback the card
        // renders is deliberately excluded, so a nameless peer gets no quick-fill row at all.
        val originalName = contactor.publicName?.takeIf { it.isNotEmpty() }
            ?: contactor.name?.takeIf { it.isNotEmpty() }
            ?: contactor.groupMemberContactor?.displayName?.takeIf { it.isNotEmpty() }
            ?: ""
        // While editing, the draft and the baseline are frozen: a refresh must never overwrite them.
        _state.update {
            it.copy(
                contactId = contactor.id,
                remarkName = remark,
                // Frozen while editing: a refresh must not change what the shortcut offers.
                quickFillName = if (it.isEditing) it.quickFillName else originalName,
                hasRemarkAvatar = !remarkAvatar.isNullOrEmpty(),
                editingName = if (it.isEditing) it.editingName else remark,
            ).withDerived()
        }
    }

    private fun exitEdit() {
        _state.update { it.copy(isEditing = false, editingName = it.remarkName, showQuickFill = false).withDerived() }
        _effect.trySend(Effect.HideKeyboard)
    }

    private fun onAvatarClick() {
        val s = _state.value
        if (!s.isEditing) return
        _effect.trySend(if (s.hasRemarkAvatar) Effect.ShowAvatarActionSheet else Effect.RequestPickAvatar)
    }

    /**
     * "Done" commits exactly what the unsaved-changes prompt considers pending ([State.hasUnsavedChanges]):
     * the trimmed input differs from the trimmed [State.editBaseline]. Empty against a stored remark
     * deletes it; anything else creates or updates it, a remark equal to the real name included.
     */
    private fun submitName(closeAfter: Boolean = false) {
        val s = _state.value
        if (s.isSubmitting) return
        val newRemark = s.editingName.trim()
        if (!s.hasUnsavedChanges) {
            exitEdit()
            if (closeAfter) _effect.trySend(Effect.Close)
            return
        }
        val contactId = loadedContactId("save name") ?: return
        val encrypted = ContactRemarkUtil.encryptRemarkV1(contactId, newRemark)
        if (newRemark.isNotEmpty() && encrypted.isEmpty()) {
            // Empty ciphertext would be sent as "clear"; never turn a typed remark into a deletion.
            L.w { "[ContactRemarkEdit] name encrypt produced empty payload uid=$contactId" }
            _effect.trySend(Effect.ShowToast(null))
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true) }
            _effect.send(Effect.ShowWait)
            try {
                val result = withContext(Dispatchers.IO) {
                    httpClient.httpService.fetchConversationSet(
                        globalServices.userManager.getUserData()?.baseAuth ?: "",
                        ConversationSetRequestBody(conversation = contactId, remark = encrypted)
                    )
                }
                _effect.send(Effect.DismissWait)
                if (result.status == 0) {
                    withContext(Dispatchers.IO) { ContactorUtil.updateRemark(contactId, encrypted) }
                    _state.update {
                        it.copy(
                            remarkName = newRemark,
                            isEditing = false,
                            editingName = newRemark,
                            showQuickFill = false,
                        ).withDerived()
                    }
                    _effect.send(Effect.HideKeyboard)
                    if (closeAfter) _effect.send(Effect.Close)
                } else {
                    L.w { "[ContactRemarkEdit] save name failed status=${result.status} reason=${result.reason} uid=$contactId" }
                    _effect.send(Effect.ShowToast(result.reason))
                }
            } catch (e: CancellationException) {
                _effect.send(Effect.DismissWait)
                throw e
            } catch (e: NetworkException) {
                _effect.send(Effect.DismissWait)
                L.w { "[ContactRemarkEdit] save name network error uid=$contactId: ${e.stackTraceToString()}" }
                _effect.send(Effect.ShowToast(e.errorMsg))
            } catch (e: Exception) {
                _effect.send(Effect.DismissWait)
                L.e { "[ContactRemarkEdit] save name failed uid=$contactId: ${e.stackTraceToString()}" }
                _effect.send(Effect.ShowToast(null))
            } finally {
                _state.update { it.copy(isSubmitting = false) }
            }
        }
    }

    private fun uploadAvatar(path: String) {
        val contactId = loadedContactId("avatar upload") ?: return
        viewModelScope.launch {
            _effect.send(Effect.ShowWait)
            try {
                val plainJson = contactAvatarUploader.uploadAndBuildJson(path)
                val encrypted = ContactRemarkUtil.encryptRemarkV1(contactId, plainJson)
                if (encrypted.isEmpty()) {
                    _effect.send(Effect.DismissWait)
                    L.w { "[ContactRemarkEdit] avatar encrypt produced empty payload uid=$contactId" }
                    _effect.send(Effect.ShowToast(null))
                    return@launch
                }
                setRemarkAvatarInternal(contactId, encrypted, uploadedPath = path)
            } catch (e: CancellationException) {
                _effect.send(Effect.DismissWait)
                throw e
            } catch (e: NetworkException) {
                _effect.send(Effect.DismissWait)
                L.w { "[ContactRemarkEdit] avatar upload network error uid=$contactId: ${e.stackTraceToString()}" }
                _effect.send(Effect.ShowToast(e.errorMsg))
            } catch (e: Exception) {
                _effect.send(Effect.DismissWait)
                L.e { "[ContactRemarkEdit] avatar upload failed uid=$contactId: ${e.stackTraceToString()}" }
                _effect.send(Effect.ShowToast(null))
            }
        }
    }

    /** `remarkAvatar = ""` clears on the server (empty = clear, null = untouched). */
    private fun setRemarkAvatar(encryptedValue: String, uploadedPath: String?) {
        val contactId = loadedContactId("reset avatar") ?: return
        viewModelScope.launch {
            _effect.send(Effect.ShowWait)
            try {
                setRemarkAvatarInternal(contactId, encryptedValue, uploadedPath)
            } catch (e: CancellationException) {
                _effect.send(Effect.DismissWait)
                throw e
            } catch (e: NetworkException) {
                _effect.send(Effect.DismissWait)
                L.w { "[ContactRemarkEdit] reset avatar network error uid=$contactId: ${e.stackTraceToString()}" }
                _effect.send(Effect.ShowToast(e.errorMsg))
            } catch (e: Exception) {
                _effect.send(Effect.DismissWait)
                L.e { "[ContactRemarkEdit] reset avatar failed uid=$contactId: ${e.stackTraceToString()}" }
                _effect.send(Effect.ShowToast(null))
            }
        }
    }

    /** Shared tail of upload / restore: server write, local write, wait dismissal. Throws on I/O. */
    private suspend fun setRemarkAvatarInternal(contactId: String, encryptedValue: String, uploadedPath: String?) {
        val result = withContext(Dispatchers.IO) {
            httpClient.httpService.fetchConversationSet(
                globalServices.userManager.getUserData()?.baseAuth ?: "",
                ConversationSetRequestBody(conversation = contactId, remarkAvatar = encryptedValue)
            )
        }
        _effect.send(Effect.DismissWait)
        if (result.status == 0) {
            withContext(Dispatchers.IO) { ContactorUtil.updateRemarkAvatar(contactId, encryptedValue) }
            _state.update { it.copy(hasRemarkAvatar = encryptedValue.isNotEmpty()) }
            if (uploadedPath != null) _effect.send(Effect.DeleteUploadTemp(uploadedPath))
        } else {
            L.w { "[ContactRemarkEdit] save avatar failed status=${result.status} reason=${result.reason} uid=$contactId" }
            _effect.send(Effect.ShowToast(result.reason))
        }
    }

    companion object {
        const val MAX_REMARK_LENGTH = 80
    }
}
