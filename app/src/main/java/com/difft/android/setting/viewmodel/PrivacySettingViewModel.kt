package com.difft.android.setting.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.network.BaseResponse
import com.difft.android.network.NetworkException
import com.difft.android.network.viewmodel.Resource
import com.difft.android.setting.data.GetProfileResponse
import com.difft.android.setting.repo.SettingRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PrivacySettingViewModel @Inject constructor() : ViewModel() {

    @Inject
    lateinit var privacySettingRepo: SettingRepo

    private val mGetStatusResultData = MutableLiveData<Resource<BaseResponse<GetProfileResponse>>>()
    internal val getStatusResultData: LiveData<Resource<BaseResponse<GetProfileResponse>>> = mGetStatusResultData

    private val mSetStatusResultData = MutableLiveData<Resource<BaseResponse<GetProfileResponse>>>()
    internal val setStatusResultData: LiveData<Resource<BaseResponse<GetProfileResponse>>> = mSetStatusResultData

    fun getStatus(token: String) {
        mGetStatusResultData.value = Resource.loading()
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    privacySettingRepo.getProfile(token)
                }
                mGetStatusResultData.value = Resource.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mGetStatusResultData.value = Resource.error(NetworkException(message = e.message ?: ""))
            }
        }
    }

    fun setStatus(token: String, searchByPhone: Int, searchByEmail: Int) {
        mSetStatusResultData.value = Resource.loading()
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    privacySettingRepo.setProfile(token, searchByPhone, searchByEmail)
                }
                mSetStatusResultData.value = Resource.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mSetStatusResultData.value = Resource.error(NetworkException(message = e.message ?: ""))
            }
        }
    }
}