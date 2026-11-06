package com.difft.android.login.repo

import android.content.Context
import com.difft.android.base.utils.globalServices
import com.difft.android.base.user.UserManager
import com.difft.android.chat.common.AvatarPickTempCleaner
import com.difft.android.chat.common.upload.ContactAvatarUploader
import com.difft.android.chat.common.upload.UploadedAvatarMeta
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.AvatarRequestBody
import com.difft.android.network.requests.ProfileRequestBody
import com.difft.android.network.responses.AvatarResponse
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

class ProfileAcceptedLocalCommitException(cause: Exception) : Exception(cause)
class ProfileSessionExpiredException : CancellationException("Profile account session changed")

/** An in-memory lease; authorization is never written to saved state or diagnostic output. */
class ProfileSession internal constructor(val accountId: String, internal val authorization: String?)

/** Shared by registration, signup and the profile editor; accepted writes finish outside UI scope. */
@Singleton
class ProfileSaveRepository @Inject constructor(
    @ChativeHttpClientModule.Chat private val httpClient: ChativeHttpClient,
    private val uploader: ContactAvatarUploader,
    private val gson: Gson,
    @ApplicationContext private val context: Context,
    @Named("application") private val applicationScope: CoroutineScope,
    private val users: UserManager,
) {
    private data class Accepted(
        val session: ProfileSession, val path: String?, val name: String?, val contact: ContactorModel?,
        val avatar: UploadedAvatarMeta?, val result: Any,
    )
    private val mutex = Mutex()
    private var accepted: Accepted? = null

    fun captureSession(): ProfileSession {
        val user = users.getUserData()
        return ProfileSession(globalServices.myId, user?.baseAuth)
    }

    private fun requireCurrent(session: ProfileSession) {
        val current = users.getUserData()
        if (session.accountId != globalServices.myId || current?.account != session.accountId || current?.baseAuth != session.authorization) {
            throw ProfileSessionExpiredException()
        }
    }

    suspend fun load(): ContactorModel? = load(captureSession())

    suspend fun load(session: ProfileSession): ContactorModel? {
        requireCurrent(session)
        return withContext(Dispatchers.IO) {
            requireCurrent(session)
            wcdb.contactor.getFirstObject(DBContactorModel.id.eq(session.accountId)).also { requireCurrent(session) }
        }
    }

    suspend fun save(filePath: String?, name: String?, contactor: ContactorModel?): Any =
        save(filePath, name, contactor, captureSession())

    suspend fun save(filePath: String?, name: String?, contactor: ContactorModel?, session: ProfileSession): Any {
        requireCurrent(session)
        return applicationScope.async(Dispatchers.IO) {
            mutex.withLock {
                requireCurrent(session)
                accepted?.let { previous ->
                    if (previous.session.accountId == session.accountId && previous.session.authorization == session.authorization) {
                        complete(previous)
                        if (previous.path == filePath && previous.name == name) return@withLock previous.result
                    } else accepted = null
                }
                requireCurrent(session)
                val meta = filePath?.let { uploader.uploadAvatar(it) }
                requireCurrent(session)
                val avatar = meta?.let {
                    gson.toJson(AvatarRequestBody(ContactAvatarUploader.AVATAR_ENC_ALGO, it.encryptionKey, it.serverId))
                }
                val response = httpClient.httpService.fetchSetProfile(ProfileRequestBody(avatar, name))
                requireCurrent(session)
                if (response.status != 0) throw NetworkException(response.status, response.reason.orEmpty())
                val candidate = Accepted(session, filePath, name, contactor, meta, meta?.serverId ?: response)
                accepted = candidate
                complete(candidate)
                candidate.result
            }
        }.await()
    }

    private fun complete(candidate: Accepted) {
        requireCurrent(candidate.session)
        try {
            candidate.contact?.let { contact ->
                contact.name = candidate.name
                val meta = candidate.avatar
                val fields = if (meta == null) arrayOf(DBContactorModel.name) else {
                    contact.avatar = gson.toJson(AvatarResponse(
                        attachmentId = meta.serverId, encAlgo = ContactAvatarUploader.AVATAR_ENC_ALGO, encKey = meta.encryptionKey,
                    ))
                    arrayOf(DBContactorModel.name, DBContactorModel.avatar)
                }
                requireCurrent(candidate.session)
                wcdb.contactor.updateObject(contact, fields, DBContactorModel.id.eq(contact.id))
                requireCurrent(candidate.session)
                ContactorUtil.emitContactsUpdate(listOf(contact.id))
            }
            requireCurrent(candidate.session)
            AvatarPickTempCleaner.deleteUploadedTemp(context, candidate.path)
            accepted = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw ProfileAcceptedLocalCommitException(e) }
    }
}
