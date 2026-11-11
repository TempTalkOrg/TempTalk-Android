package com.difft.android.login.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.login.repo.ProfileSaveRepository
import com.difft.android.network.NetworkException
import com.difft.android.network.viewmodel.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.difft.app.database.models.ContactorModel
import javax.inject.Inject


@HiltViewModel
class ContactProfileSettingViewModel @Inject constructor(
    private val repository: ProfileSaveRepository,
) : ViewModel() {
    private val result = MutableLiveData<Resource<Any>>()
    internal val setProfileResultData: LiveData<Resource<Any>> = result

    fun setProfile(filePath: String?, name: String?, contactor: ContactorModel?) {
        val session = repository.captureSession()
        result.value = Resource.loading()
        viewModelScope.launch {
            try {
                result.value = Resource.success(repository.save(filePath, name, contactor, session))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[Profile] save_failed error=${e.javaClass.simpleName}" }
                val message = if (e is com.difft.android.login.repo.ProfileAcceptedLocalCommitException) {
                    com.difft.android.base.utils.ResUtils.getString(com.difft.android.base.R.string.settings_accepted_local_commit_failed)
                } else e.message.orEmpty()
                result.value = Resource.error(e as? NetworkException ?: NetworkException(message = message))
            }
        }
    }
}
