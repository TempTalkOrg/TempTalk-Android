package com.difft.android.chat.group

import com.difft.android.base.utils.globalServices

import android.content.Context
import android.util.Base64
import androidx.core.net.toUri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.common.GroupAvatarUtil
import com.difft.android.chat.util.MediaUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.GroupAvatarData
import com.difft.android.network.group.GroupAvatarResponse
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads a local avatar file to the CDN and returns the plaintext avatar JSON
 * suitable for `CreateGroupReq.avatar` or `ChangeGroupSettingsReq.avatar`.
 *
 * For encrypted groups the caller should pass the returned JSON through
 * [com.difft.android.chat.crypto.GroupCrypto.encryptGroupAvatar] and put the
 * ciphertext in `encryptedAvatar` instead.
 *
 * Error contract:
 *  - [NetworkException] is thrown only when the server returns `status != 0` —
 *    its `message` carries the server-provided reason and is safe to surface to the user.
 *  - All local failures (file read, encrypt) throw [IOException] with an English
 *    debugging message meant for logs. The caller should fall back to a generic
 *    localized toast (e.g. `R.string.chat_net_error`) and not display the message.
 */
@Singleton
class GroupAvatarUploader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
    @param:ChativeHttpClientModule.NoHeader private val noHeaderClient: ChativeHttpClient,
    private val gson: Gson,
) {
    suspend fun uploadAndBuildJson(filePath: String): String = withContext(Dispatchers.IO) {
        val response = httpClient.httpService
            .fetchAttachmentInfo((globalServices.userManager.getUserData()?.baseAuth ?: ""))
        if (response.status != 0) {
            L.w { "[GroupAvatarUploader] fetchAttachmentInfo failed: status=${response.status}, reason=${response.reason}" }
            throw NetworkException(response.status, response.reason ?: "")
        }

        val data = FileUtil.readFile(File(filePath))
        if (data == null) {
            L.w { "[GroupAvatarUploader] failed to read avatar file: $filePath" }
            throw IOException("avatar_read_failed")
        }

        val encryptResult = GroupAvatarUtil.encryptGroupAvatar(data)
        val encryptionKey = encryptResult["encryptionKey"] as? String
        val digest = encryptResult["digest"] as? String
        val encryptedData = encryptResult["encryptedData"] as? ByteArray
        if (encryptionKey == null || digest == null || encryptedData == null) {
            L.w {
                "[GroupAvatarUploader] encrypt result missing fields: " +
                    "key=${encryptionKey != null}, digest=${digest != null}, data=${encryptedData != null}"
            }
            throw IOException("avatar_encrypt_failed")
        }

        // Close the returned ResponseBody to release the OkHttp connection resource.
        // The suspend call only returns here after the upload (request body) has been
        // fully sent and the server returned 2xx; close() only discards the (empty/
        // irrelevant) response body. Non-2xx would have already thrown HttpException.
        noHeaderClient.httpService.fetchUploadAvatar(
            response.location.orEmpty(),
            RequestBody.create(null, encryptedData)
        ).close()

        val avatarData = GroupAvatarData(
            0,
            data.size.toString(),
            MediaUtil.getMimeType(context, filePath.toUri()),
            digest,
            encryptionKey,
            response.id.toString()
        )
        val avatarDataString = Base64.encodeToString(
            gson.toJson(avatarData).toByteArray(),
            Base64.NO_WRAP
        )
        gson.toJson(GroupAvatarResponse(avatarDataString))
    }
}
