package com.difft.android.setting.viewmodel

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.difft.android.base.utils.RxUtil
import com.difft.android.network.BaseResponse
import com.difft.android.network.NetworkException
import com.difft.android.network.viewmodel.Resource
import com.difft.android.setting.data.GetProfileResponse
import com.difft.android.setting.repo.SettingRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PrivacySettingViewModel @Inject constructor() : ViewModel() {

    @Inject
    lateinit var privacySettingRepo: SettingRepo

    private val mGetStatusResultData = MutableLiveData<Resource<BaseResponse<GetProfileResponse>>>()
    internal val getStatusResultData: LiveData<Resource<BaseResponse<GetProfileResponse>>> = mGetStatusResultData

    private val mSetStatusResultData = MutableLiveData<Resource<BaseResponse<GetProfileResponse>>>()
    internal val setStatusResultData: LiveData<Resource<BaseResponse<GetProfileResponse>>> = mSetStatusResultData

    fun getStatus(context: Context, token: String) {
        mGetStatusResultData.value = Resource.loading()
        privacySettingRepo.getProfile(token)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(context as LifecycleOwner))
            .subscribe({
                mGetStatusResultData.value = Resource.success(it)
            }) {
                mGetStatusResultData.value = Resource.error(NetworkException(message = it.message ?: ""))
            }
    }

    fun setStatus(context: Context, token: String, searchByPhone: Int, searchByEmail: Int) {
        mSetStatusResultData.value = Resource.loading()
        privacySettingRepo.setProfile(token, searchByPhone, searchByEmail)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(context as LifecycleOwner))
            .subscribe({
                mSetStatusResultData.value = Resource.success(it)
            }) {
                mSetStatusResultData.value = Resource.error(NetworkException(message = it.message ?: ""))
            }
    }
}