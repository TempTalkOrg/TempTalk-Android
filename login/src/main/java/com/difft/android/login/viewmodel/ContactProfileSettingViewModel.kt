package com.difft.android.login.viewmodel

import android.app.Activity
import android.util.Base64
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.RxUtil
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
import io.reactivex.rxjava3.core.Single
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

//    private val mContactorResultData = MutableLiveData<Resource<Contactor>>()
//    internal val contactorResultData: LiveData<Resource<Contactor>> = mContactorResultData

    private val mSetProfileResultData = MutableLiveData<Resource<Any>>()
    internal val setProfileResultData: LiveData<Resource<Any>> = mSetProfileResultData

//    fun getContactorInfo(context: Context, contactorID: String) {
//        mContactorResultData.value = Resource.loading()
//        ContactorUtil.fetchContactors(listOf(contactorID), context)
//            .compose(RxUtil.getSingleSchedulerComposer())
//            .to(RxUtil.autoDispose(context as LifecycleOwner))
//            .subscribe({
//                mContactorResultData.value = Resource.success(it.first())
//            }) {
//                mContactorResultData.value = Resource.error(NetworkException(message = it.message ?: ""))
//            }
//    }

    fun setProfile(
        context: Activity,
        filePath: String?,
        name: String?,
        contactor: ContactorModel?
    ) {
        val basicAuth = SecureSharedPrefsUtil.getBasicAuth()
        mSetProfileResultData.value = Resource.loading()
        if (null == filePath) {
            httpClient.httpService.fetchSetProfile(
                basicAuth,
                ProfileRequestBody(avatar = null, name = name)
            )
                .compose(RxUtil.getSingleSchedulerComposer())
                .to(RxUtil.autoDispose(context as LifecycleOwner))
                .subscribe({
                    if (it.status == 0) {
                        contactor?.let {
                            contactor.name = name
                            wcdb.contactor.updateObject(contactor, arrayOf(DBContactorModel.name), DBContactorModel.id.eq(contactor.id))
                            ContactorUtil.emitContactsUpdate(listOf(contactor.id))
                        }
                        mSetProfileResultData.value = Resource.success(it)
                    } else {
                        mSetProfileResultData.value = Resource.error(NetworkException(it.status, it.reason ?: ""))
                    }
                }) {
                    it.printStackTrace()
                    mSetProfileResultData.value = Resource.error(NetworkException(message = it.message ?: ""))
                }
        } else {
            val keyStr = Util.getSecret(32)
            httpClient.httpService.fetchAvatarAttachmentInfo(basicAuth)
                .concatMap { response ->
                    if (response.status == 0) {
                        val encrypt = FileUtil.readFile(File(filePath))?.let {
                            encrypt(it, keyStr)
                        }
                        if (encrypt != null) {
                            noHeaderClient.httpService.fetchUploadAvatar(
                                response.location ?: "",
                                RequestBody.create(null, encrypt)
                            ).map { it to response.id }
                        } else {
                            Single.error(NetworkException(message = "encrypt avatar file error"))
                        }
                    } else {
                        Single.error(NetworkException(response.status, response.reason ?: ""))
                    }
                }
                .concatMap { response ->
                    val avatar = Gson().toJson(AvatarRequestBody("AESGCM256", keyStr, response.second.toString()))
                    httpClient.httpService.fetchSetProfile(basicAuth, ProfileRequestBody(avatar, name))
                        .map { it to response.second }
                }
                .compose(RxUtil.getSingleSchedulerComposer())
                .to(RxUtil.autoDispose(context as LifecycleOwner))
                .subscribe({ response ->
                    if (response.first.status == 0) {
                        contactor?.let {
                            contactor.name = name
                            contactor.avatar = Gson().toJson(AvatarResponse(attachmentId = response.second.toString(), encAlgo = "AESGCM256", encKey = keyStr))
                            wcdb.contactor.updateObject(
                                contactor, arrayOf(
                                    DBContactorModel.name,
                                    DBContactorModel.avatar,
                                ), DBContactorModel.id.eq(contactor.id)
                            )
                            ContactorUtil.emitContactsUpdate(listOf(contactor.id))
                        }
                        mSetProfileResultData.value = Resource.success(response.second)
                    } else {
                        mSetProfileResultData.value = Resource.error(NetworkException(response.first.status, response.first.reason ?: ""))
                    }
                }) {
                    it.printStackTrace()
                    mSetProfileResultData.value = Resource.error(NetworkException(message = it.message ?: ""))
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
            L.w { "avatar encrypt error:" + e.stackTraceToString() }
            e.printStackTrace()
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