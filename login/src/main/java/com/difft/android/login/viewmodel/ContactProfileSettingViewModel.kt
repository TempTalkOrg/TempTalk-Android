package com.difft.android.login.viewmodel

import android.app.Activity
import android.util.Base64
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import org.difft.app.database.wcdb
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
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.thoughtcrime.securesms.util.Util
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

@HiltViewModel
class ContactProfileSettingViewModel @Inject constructor() : ViewModel() {

    @ChativeHttpClientModule.Chat
    @Inject
    lateinit var httpClient: ChativeHttpClient

    @Inject
    @ChativeHttpClientModule.NoHeader
    lateinit var noHeaderClient: ChativeHttpClient

    @Inject
    lateinit var urlManager: UrlManager

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
                    ).await()

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
                    // 更新头像和名称
                    val keyStr = Util.getSecret(32)

                    // Step 1: 获取头像上传信息
                    val attachmentResponse = httpClient.httpService.fetchAvatarAttachmentInfo(basicAuth).await()
                    if (attachmentResponse.status != 0) {
                        mSetProfileResultData.value = Resource.error(NetworkException(attachmentResponse.status, attachmentResponse.reason ?: ""))
                        return@launch
                    }

                    // Step 2: 加密并上传头像
                    val encrypt = withContext(Dispatchers.IO) {
                        FileUtil.readFile(File(filePath))?.let { encrypt(it, keyStr) }
                    }
                    if (encrypt == null) {
                        mSetProfileResultData.value = Resource.error(NetworkException(message = "encrypt avatar file error"))
                        return@launch
                    }

                    noHeaderClient.httpService.fetchUploadAvatar(
                        attachmentResponse.location ?: "",
                        RequestBody.create(null, encrypt)
                    ).await()

                    // Step 3: 设置 profile
                    val avatar = Gson().toJson(AvatarRequestBody("AESGCM256", keyStr, attachmentResponse.id.toString()))
                    val profileResult = httpClient.httpService.fetchSetProfile(basicAuth, ProfileRequestBody(avatar, name)).await()

                    if (profileResult.status == 0) {
                        contactor?.let {
                            contactor.name = name
                            contactor.avatar = Gson().toJson(AvatarResponse(attachmentId = attachmentResponse.id.toString(), encAlgo = "AESGCM256", encKey = keyStr))
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
                        mSetProfileResultData.value = Resource.success(attachmentResponse.id)
                    } else {
                        mSetProfileResultData.value = Resource.error(NetworkException(profileResult.status, profileResult.reason ?: ""))
                    }
                }
            } catch (e: Exception) {
                L.w(e) { "[ContactProfileSettingViewModel] error:" }
                mSetProfileResultData.value = Resource.error(NetworkException(message = e.message ?: ""))
            }
        }
    }

    private fun encrypt(contentBytes: ByteArray, key: String?): ByteArray? {
        try {
            val iv = ByteArray(12)
            val secureRandom = SecureRandom()
            secureRandom.nextBytes(iv)
            val params = GCMParameterSpec(128, iv)
            val secretKey = SecretKeySpec(Base64.decode(key, Base64.DEFAULT), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, params)
            val encryptData: ByteArray = cipher.doFinal(contentBytes)
            assert(encryptData.size == contentBytes.size + 16)
            val message = ByteArray(12 + contentBytes.size + 16)
            System.arraycopy(iv, 0, message, 0, 12)
            System.arraycopy(encryptData, 0, message, 12, encryptData.size)
            return message
            //return Base64.getEncoder().encodeToString(message);
        } catch (e: Exception) {
            L.w(e) { "avatar encrypt error:" }
        }
        return null
    }

//    private fun getSecret(size: Int): String? {
//        val secret = getSecretBytes(size)
//        return String(Base64.encode(secret, Base64.DEFAULT))
//    }
//
//    private fun getSecretBytes(size: Int): ByteArray {
//        val secret = ByteArray(size)
//        SecureRandom.getInstance("SHA1PRNG").nextBytes(secret)
//        return secret
//    }

}