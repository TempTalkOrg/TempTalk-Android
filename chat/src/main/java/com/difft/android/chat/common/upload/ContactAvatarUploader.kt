package com.difft.android.chat.common.upload

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.Base64
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.chat.util.Util
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.responses.AvatarResponse
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypt + upload a contact-class avatar (own profile avatar or remark avatar
 * for another contact). AES-GCM-256, endpoint `/v1/profile/avatar/attachment`.
 *
 * Kept separate from [com.difft.android.chat.group.GroupAvatarUploader]: groups
 * use a different endpoint, encryption scheme, and CDN. Mixing the two surfaces
 * as `IllegalStateException: closed` from OkHttp on download.
 */
@Singleton
class ContactAvatarUploader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
    @param:ChativeHttpClientModule.NoHeader private val noHeaderClient: ChativeHttpClient,
    private val gson: Gson,
) {
    suspend fun uploadAvatar(filePath: String): UploadedAvatarMeta = withContext(Dispatchers.IO) {
        val response = httpClient.httpService
            .fetchAvatarAttachmentInfo(SecureSharedPrefsUtil.getBasicAuth())
        if (response.status != 0) {
            L.w { "[ContactAvatarUploader] fetchAvatarAttachmentInfo failed: status=${response.status}, reason=${response.reason}" }
            throw NetworkException(response.status, response.reason ?: "")
        }

        val data = FileUtil.readFile(File(filePath))
        if (data == null) {
            L.w { "[ContactAvatarUploader] failed to read avatar file: $filePath" }
            throw IOException("avatar_read_failed")
        }

        val keyBase64 = Util.getSecret(32)
        val payload = try {
            encryptGcm(data, keyBase64)
        } catch (e: Exception) {
            L.w { "[ContactAvatarUploader] GCM encrypt failed: ${e.stackTraceToString()}" }
            throw IOException("avatar_encrypt_failed")
        }

        // close() releases the OkHttp connection; non-2xx already threw HttpException.
        noHeaderClient.httpService.fetchUploadAvatar(
            response.location.orEmpty(),
            RequestBody.create(null, payload)
        ).close()

        UploadedAvatarMeta(
            serverId = response.id.toString(),
            encryptionKey = keyBase64,
        )
    }

    /** Upload and return the canonical [AvatarResponse] JSON. */
    suspend fun uploadAndBuildJson(filePath: String): String {
        val meta = uploadAvatar(filePath)
        return gson.toJson(
            AvatarResponse(
                attachmentId = meta.serverId,
                encAlgo = AVATAR_ENC_ALGO,
                encKey = meta.encryptionKey,
            )
        )
    }

    /** Returns `IV (12B) ‖ ciphertext ‖ GCM tag (16B)`. Paired with [com.difft.android.chat.common.AvatarUtil.fetchAvatar]. */
    private fun encryptGcm(content: ByteArray, base64Key: String): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val secretKey = SecretKeySpec(Base64.decode(base64Key), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertextWithTag = cipher.doFinal(content)
        return iv + ciphertextWithTag
    }

    companion object {
        const val AVATAR_ENC_ALGO: String = "AESGCM256"
    }
}

data class UploadedAvatarMeta(
    val serverId: String,
    val encryptionKey: String,
)
