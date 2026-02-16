package com.difft.android.setting.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.widget.ToastUtil
import com.difft.android.setting.repo.SettingRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import javax.inject.Inject


@HiltViewModel
class SetCustomIdViewModel@Inject constructor() : ViewModel() {

    @Inject
    lateinit var settingRepo: SettingRepo

    private val _responseStatus = MutableStateFlow(0)
    val responseStatus = _responseStatus.asStateFlow()

    private val _errorTip = MutableStateFlow<String?>(null)
    val errorTip = _errorTip.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    fun submitCustomUid(customUid: String, callback: (Int) -> Unit = {}) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = SecureSharedPrefsUtil.getToken()
                val response = settingRepo.setProfile(token = token, customUid = customUid).await()
                if (response.status != 0) {
                    L.e {"[Settings] submitCustomUid status:${response.status}, reason:${response.reason} "}
                    when (response.status) {
                        10301 -> {
                            setErrorTip(ResUtils.getString(R.string.settings_contact_ID_status_uid_exists, response.data?.recommendUid))
                        }
                        10302 -> {
                            setErrorTip(ResUtils.getString(R.string.settings_contact_ID_status_uid_limited))
                        }
                        else -> {
                            setErrorTip(response.reason)
                        }
                    }
                } else {
                    showSubmitStatus(ResUtils.getString(R.string.settings_contact_ID_status_success))
                }
                _responseStatus.value = response.status
                callback.invoke(response.status)
            } catch (e: Exception) {
                L.e { "[Settings] submitCustomUid error:${e.stackTraceToString()}"}
                showSubmitStatus(ResUtils.getString(R.string.settings_contact_ID_status_failed))
                callback.invoke(-1)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resetResponseStatus() {
        _responseStatus.value = 0
    }

    fun resetErrorTip() {
        _errorTip.value = null
    }

    fun setErrorTip(errorTip: String?) {
        _errorTip.value = errorTip
    }

    private fun showSubmitStatus( messageString: String) {
        viewModelScope.launch(Dispatchers.Main) {
            ToastUtil.show(messageString)
        }
    }

}