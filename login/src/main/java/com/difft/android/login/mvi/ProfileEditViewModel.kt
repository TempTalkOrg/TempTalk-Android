package com.difft.android.login.mvi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.login.repo.ProfileSaveRepository
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val repository: ProfileSaveRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val session = repository.captureSession().also { captured ->
        val draftAccount = saved.get<String>("profileAccount")
        if (draftAccount != null && draftAccount != captured.accountId) {
            listOf("loaded", "name", "avatar", "nameDirty", "avatarDirty", "picker", "completed").forEach { saved.remove<Any>(it) }
        }
        saved["profileAccount"] = captured.accountId
    }
    private val _state = MutableStateFlow(ProfileEditContract.State(
        initialLoaded = saved["loaded"] ?: false,
        nameDraft = saved["name"] ?: "",
        avatarPath = saved["avatar"],
        nameDirty = saved["nameDirty"] ?: false,
        avatarDirty = saved["avatarDirty"] ?: false,
        pickerPending = saved["picker"] ?: false,
        completed = saved["completed"] ?: false,
    ))
    val state = _state.asStateFlow()
    private val _effect = Channel<ProfileEditContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    init { dispatch(ProfileEditContract.Intent.Load) }

    fun dispatch(intent: ProfileEditContract.Intent) {
        when (intent) {
            ProfileEditContract.Intent.Load -> load()
            is ProfileEditContract.Intent.ChangeName -> change {
                it.copy(nameDraft = intent.name, nameDirty = true, completed = false)
            }
            is ProfileEditContract.Intent.SelectAvatar -> change {
                it.copy(avatarPath = intent.path, avatarDirty = true, pickerPending = false, completed = false)
            }
            is ProfileEditContract.Intent.SetPickerPending -> change { it.copy(pickerPending = intent.pending) }
            ProfileEditContract.Intent.Save -> save()
        }
    }

    private fun change(reduce: (ProfileEditContract.State) -> ProfileEditContract.State) {
        _state.update(reduce)
        state.value.let {
            saved["loaded"] = it.initialLoaded
            saved["name"] = it.nameDraft
            saved["avatar"] = it.avatarPath
            saved["nameDirty"] = it.nameDirty
            saved["avatarDirty"] = it.avatarDirty
            saved["picker"] = it.pickerPending
            saved["completed"] = it.completed
        }
    }

    private fun load() {
        if (state.value.contact != null) return
        viewModelScope.launch {
            try {
                val contact = repository.load(session)
                change { current -> current.copy(
                    initialLoaded = true,
                    contact = contact,
                    nameDraft = if (current.nameDirty || current.initialLoaded) current.nameDraft else contact?.getDisplayNameForUI().orEmpty(),
                ) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _effect.send(ProfileEditContract.Effect.Failed(e.message)) }
        }
    }

    private fun save() {
        val request = state.value
        if (request.busy || request.completed || request.nameDraft.isBlank()) return
        if (request.avatarPath != null && !File(request.avatarPath).canRead() && !request.acceptedLocalPending) {
            _effect.trySend(ProfileEditContract.Effect.ReselectAvatar)
            return
        }
        change { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                repository.save(request.avatarPath, request.nameDraft.trim(), request.contact ?: repository.load(session), session)
                val refreshed = try { repository.load(session) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                change { it.copy(busy = false, contact = refreshed ?: it.contact, completed = true, acceptedLocalPending = false, avatarPath = null, nameDirty = false, avatarDirty = false) }
                _effect.send(ProfileEditContract.Effect.Saved)
            } catch (e: CancellationException) { change { it.copy(busy = false) }; throw e }
            catch (e: Exception) {
                change { it.copy(busy = false, acceptedLocalPending = e is com.difft.android.login.repo.ProfileAcceptedLocalCommitException) }
                _effect.send(if (e is com.difft.android.login.repo.ProfileAcceptedLocalCommitException) ProfileEditContract.Effect.AcceptedLocalCommitFailed else ProfileEditContract.Effect.Failed(e.message))
            }
        }
    }
}
