package com.difft.android.chat.group


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
















@Singleton
class GroupAvatarUploader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
    @param:ChativeHttpClientModule.NoHeader private val noHeaderClient: ChativeHttpClient,
    private val gson: Gson,
) {
    suspend fun uploadAndBuildJson(filePath: String): String = withContext(Dispatchers.IO) {
        val response = httpClient.httpService
            .fetchAttachmentInfo()
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
