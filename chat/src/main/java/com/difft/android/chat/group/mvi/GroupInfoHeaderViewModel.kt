package com.difft.android.chat.group.mvi

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarPickTempCleaner
import com.difft.android.chat.crypto.GroupCrypto
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.group.GroupAvatarUploader
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.group.getDisplayAvatarData
import com.difft.android.chat.group.mvi.GroupInfoHeaderContract.Effect
import com.difft.android.chat.group.mvi.GroupInfoHeaderContract.ExitSource
import com.difft.android.chat.group.mvi.GroupInfoHeaderContract.Intent
import com.difft.android.chat.group.mvi.GroupInfoHeaderContract.State
import com.difft.android.network.NetworkException
import com.difft.android.network.group.ChangeGroupSettingsReq
import com.difft.android.network.group.GroupRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.GroupModel



@HiltViewModel
class GroupInfoHeaderViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val groupRepo: GroupRepo,
    private val groupUtil: GroupUtil,
    private val groupCryptoRepo: GroupCryptoRepo,
    private val groupAvatarUploader: GroupAvatarUploader,
) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    
    private var loadJob: Job? = null

    
    private var mutationsInFlight = 0

    
    private var deferredLoad: GroupModel? = null

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.Load -> load(intent.group)
            Intent.EnterEdit -> enterEdit()
            is Intent.ChangeName -> changeName(intent.value)
            Intent.SubmitName -> submitName()
            Intent.DiscardEdit -> exitEdit()
            Intent.AvatarClick -> onAvatarClick()
            is Intent.AvatarPicked -> uploadAvatar(intent.path)
            is Intent.RequestExit -> requestExit(intent.source)
            is Intent.DiscardExit -> {
                exitEdit()
                if (intent.source == ExitSource.Back) _effect.trySend(Effect.Finish)
            }

            is Intent.SaveExit -> submitName(exitSource = intent.source)
        }
    }

    private fun enterEdit() {
        _state.update {
            it.copy(isEditing = true, editBaseline = it.name, editingName = it.name, hasEditedInput = false).withDerived()
        }
    }

    private fun changeName(value: String) {
        _state.update {
            it.copy(editingName = value, hasEditedInput = it.hasEditedInput || value != it.editingName).withDerived()
        }
    }

    
    private fun requestExit(source: ExitSource) {
        val s = _state.value
        if (s.hasUnsavedChanges) {
            _effect.trySend(Effect.ShowUnsavedDialog(source))
            return
        }
        if (s.isEditing) exitEdit()
        if (source == ExitSource.Back) _effect.trySend(Effect.Finish)
    }

    private fun State.withDerived(): State =
        copy(hasUnsavedChanges = isEditing && hasEditedInput && editingName.trim() != editBaseline.trim())

    private fun load(group: GroupModel) {
        val gid = group.gid ?: return
        if (mutationsInFlight > 0) {
            
            
            deferredLoad = group
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val encrypted = (group.groupCryptoMode ?: 0) > 0
            val hasKey = encrypted && withContext(Dispatchers.IO) { groupCryptoRepo.getRGroupBytes(gid) != null }
            val name = group.name.orEmpty()
            val previousLocalAvatar = _state.value.pendingLocalAvatarPath
            _state.update {
                it.copy(
                    groupId = gid,
                    name = name,
                    avatarData = group.getDisplayAvatarData(),
                    pendingLocalAvatarPath = null,
                    isEncrypted = encrypted,
                    nameEditable = !encrypted || hasKey,
                    avatarEditable = hasKey,
                    editingName = if (it.isEditing) it.editingName else name,
                )
            }
            
            previousLocalAvatar?.let { AvatarPickTempCleaner.deleteUploadedTemp(appContext, it) }
        }
    }

    
    private fun beginMutation() {
        mutationsInFlight++
        loadJob?.cancel()
    }

    



    private fun endMutation(succeeded: Boolean) {
        mutationsInFlight--
        if (succeeded) deferredLoad = null
        if (mutationsInFlight == 0) deferredLoad?.let { deferredLoad = null; load(it) }
    }

    override fun onCleared() {
        _state.value.pendingLocalAvatarPath?.let { AvatarPickTempCleaner.deleteUploadedTemp(appContext, it) }
        super.onCleared()
    }

    private fun exitEdit() {
        _state.update { it.copy(isEditing = false, editingName = it.name, hasEditedInput = false).withDerived() }
        _effect.trySend(Effect.HideKeyboard)
    }

    private fun onAvatarClick() {
        val s = _state.value
        when {
            s.isEditing && s.avatarEditable -> _effect.trySend(Effect.RequestPickAvatar)
            !s.isEditing && s.avatarData != null -> _effect.trySend(Effect.PreviewAvatar(s.avatarData))
        }
    }

    



    private fun submitName(exitSource: ExitSource? = null) {
        val s = _state.value
        if (s.isSubmitting) return
        val newName = s.editingName.trim()
        when {
            newName == s.editBaseline.trim() -> {
                exitEdit()
                if (exitSource == ExitSource.Back) _effect.trySend(Effect.Finish)
                return
            }

            newName.isEmpty() -> {
                _effect.trySend(Effect.ShowToastRes(R.string.group_edit_name_empty))
                return
            }

            newName.length > MAX_GROUP_NAME_LENGTH -> {
                _effect.trySend(Effect.ShowToastRes(R.string.chat_group_name_too_long))
                return
            }
        }

        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true) }
            beginMutation()
            var settled = false
            _effect.send(Effect.ShowWait)
            try {
                val request = withContext(Dispatchers.IO) { buildNameChangeRequest(s.groupId, newName) }
                val response = groupRepo.changeGroupSettings(s.groupId, request)
                _effect.send(Effect.DismissWait)
                if (response.status == 0) {
                    _state.update {
                        it.copy(name = newName, isEditing = false, editingName = newName, hasEditedInput = false).withDerived()
                    }
                    _effect.send(Effect.HideKeyboard)
                    
                    settled = true
                    endMutation(succeeded = true)
                    
                    if (exitSource == ExitSource.Back) _effect.send(Effect.Finish)
                    groupUtil.fetchAndSaveSingleGroupInfo(s.groupId, true)
                } else {
                    L.w { "[GroupInfoHeader] name change failed gid=${s.groupId} status=${response.status} reason=${response.reason}" }
                    _effect.send(Effect.ShowToast(response.reason))
                }
            } catch (e: CancellationException) {
                _effect.send(Effect.DismissWait)
                throw e
            } catch (e: Exception) {
                _effect.send(Effect.DismissWait)
                L.w { "[GroupInfoHeader] name change error gid=${s.groupId}: ${e.stackTraceToString()}" }
                _effect.send(Effect.ShowToast((e as? NetworkException)?.errorMsg))
            } finally {
                _state.update { it.copy(isSubmitting = false) }
                if (!settled) endMutation(succeeded = false)
            }
        }
    }

    private fun uploadAvatar(path: String) {
        val gid = _state.value.groupId
        viewModelScope.launch {
            beginMutation()
            var settled = false
            _effect.send(Effect.ShowWait)
            try {
                val avatarJson = groupAvatarUploader.uploadAndBuildJson(path)
                val request = withContext(Dispatchers.IO) { buildAvatarChangeRequest(gid, avatarJson) }
                val response = groupRepo.changeGroupSettings(gid, request)
                _effect.send(Effect.DismissWait)
                if (response.status == 0) {
                    
                    
                    _state.value.pendingLocalAvatarPath?.takeIf { it != path }
                        ?.let { AvatarPickTempCleaner.deleteUploadedTemp(appContext, it) }
                    _state.update { it.copy(pendingLocalAvatarPath = path) }
                    settled = true
                    endMutation(succeeded = true)
                    groupUtil.fetchAndSaveSingleGroupInfo(gid, true)
                } else {
                    L.w { "[GroupInfoHeader] avatar change failed gid=$gid status=${response.status} reason=${response.reason}" }
                    _effect.send(Effect.ShowToast(response.reason))
                }
            } catch (e: CancellationException) {
                _effect.send(Effect.DismissWait)
                throw e
            } catch (e: Exception) {
                _effect.send(Effect.DismissWait)
                L.w { "[GroupInfoHeader] avatar change error gid=$gid: ${e.stackTraceToString()}" }
                _effect.send(Effect.ShowToast((e as? NetworkException)?.errorMsg))
            } finally {
                if (!settled) endMutation(succeeded = false)
            }
        }
    }

    private fun buildNameChangeRequest(gid: String, newName: String): ChangeGroupSettingsReq {
        if (!_state.value.isEncrypted) {
            return ChangeGroupSettingsReq(name = newName)
        }
        val rGroupBytes = groupCryptoRepo.getRGroupBytes(gid)
            ?: throw IllegalStateException("no_encryption_key")
        val kGroup = GroupCrypto.deriveKGroup(rGroupBytes)
        return ChangeGroupSettingsReq(encryptedName = GroupCrypto.encryptGroupName(kGroup, newName))
    }

    
    private fun buildAvatarChangeRequest(gid: String, avatarJson: String): ChangeGroupSettingsReq {
        val rGroupBytes = groupCryptoRepo.getRGroupBytes(gid)
            ?: throw IllegalStateException("no_encryption_key")
        val kGroup = GroupCrypto.deriveKGroup(rGroupBytes)
        return ChangeGroupSettingsReq(encryptedAvatar = GroupCrypto.encryptGroupAvatar(kGroup, avatarJson))
    }

    companion object {
        const val MAX_GROUP_NAME_LENGTH = 64
    }
}
