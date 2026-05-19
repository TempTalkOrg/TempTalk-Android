package com.difft.android.login.viewmodel

import android.app.Activity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureSharedPrefsUtil
import org.difft.app.database.wcdb
import com.difft.android.chat.common.upload.ContactAvatarUploader
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.UrlManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.AvatarRequestBody
import com.difft.android.network.requests.ProfileRequestBody
import com.difft.android.network.responses.AvatarResponse
import com.difft.android.network.viewmodel.Resource
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import javax.inject.Inject

@HiltViewModel
class ContactProfileSettingViewModel @Inject constructor() : ViewModel() {

    @ChativeHttpClientModule.Chat
    @Inject
    lateinit var httpClient: ChativeHttpClient

    @Inject
    lateinit var urlManager: UrlManager

    @Inject
    lateinit var contactAvatarUploader: ContactAvatarUploader

    private val mSetProfileResultData = MutableLiveData<Resource<Any>>()
    internal val setProfileResultData: LiveData<Resource<Any>> = mSetProfileResultData

    fun setProfile(
        context: Activity,
        filePath: String?,
        name: String?,
        contactor: ContactorModel?
    ) {
        val basicAuth = SecureSharedPrefsUtil.getBasicAuth()
        mSetProfileResultData.value = Resource.loading()

        viewModelScope.launch {
            try {
                if (filePath == null) {
                    // 仅更新名称
                    val result = httpClient.httpService.fetchSetProfile(
                        basicAuth,
                        ProfileRequestBody(avatar = null, name = name)
                    )

                    if (result.status == 0) {
                        contactor?.let {
                            contactor.name = name
                            withContext(Dispatchers.IO) {
                                wcdb.contactor.updateObject(contactor, arrayOf(DBContactorModel.name), DBContactorModel.id.eq(contactor.id))
                            }
                            ContactorUtil.emitContactsUpdate(listOf(contactor.id))
                        }
                        mSetProfileResultData.value = Resource.success(result)
                    } else {
                        mSetProfileResultData.value = Resource.error(NetworkException(result.status, result.reason ?: ""))
                    }
                } else {
                    val meta = contactAvatarUploader.uploadAvatar(filePath)

                    val avatar = Gson().toJson(
                        AvatarRequestBody(
                            encAlgo = ContactAvatarUploader.AVATAR_ENC_ALGO,
                            encKey = meta.encryptionKey,
                            attachmentId = meta.serverId,
                        )
                    )
                    val profileResult = httpClient.httpService.fetchSetProfile(basicAuth, ProfileRequestBody(avatar, name))

                    if (profileResult.status == 0) {
                        contactor?.let {
                            contactor.name = name
                            contactor.avatar = Gson().toJson(
                                AvatarResponse(
                                    attachmentId = meta.serverId,
                                    encAlgo = ContactAvatarUploader.AVATAR_ENC_ALGO,
                                    encKey = meta.encryptionKey,
                                )
                            )
                            withContext(Dispatchers.IO) {
                                wcdb.contactor.updateObject(
                                    contactor, arrayOf(
                                        DBContactorModel.name,
                                        DBContactorModel.avatar,
                                    ), DBContactorModel.id.eq(contactor.id)
                                )
                            }
                            ContactorUtil.emitContactsUpdate(listOf(contactor.id))
                        }
                        mSetProfileResultData.value = Resource.success(meta.serverId)
                    } else {
                        mSetProfileResultData.value = Resource.error(NetworkException(profileResult.status, profileResult.reason ?: ""))
                    }
                }
            } catch (e: NetworkException) {
                L.w { "[ContactProfileSettingViewModel] network error: ${e.stackTraceToString()}" }
                mSetProfileResultData.value = Resource.error(e)
            } catch (e: Exception) {
                L.w { "[ContactProfileSettingViewModel] error: ${e.stackTraceToString()}" }
                mSetProfileResultData.value = Resource.error(NetworkException(message = e.message ?: ""))
            }
        }
    }
}
