package com.difft.android.call.manager

import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.call.LCallToChatController
import com.difft.android.call.R
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通讯录缓存管理器
 * 负责管理通话相关的通讯录缓存和显示信息
 */
@Singleton
class ContactorCacheManager @Inject constructor() {
    
    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val callToChatController: LCallToChatController
    }
    
    private val callToChatController: LCallToChatController by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance).callToChatController
    }
    
    private val application = ApplicationHelper.instance
    
    // 通讯录缓存：userId -> ContactorModel
    private val contactorCache: ConcurrentHashMap<String, CacheEntry> = ConcurrentHashMap()

    private sealed class CacheEntry {
        data class Hit(val contactor: ContactorModel) : CacheEntry()
        data object Miss : CacheEntry()
    }
    
    /**
     * 更新通讯录缓存
     * 批量更新指定用户ID列表的通讯录信息
     * 
     * @param uidList 需要更新的用户ID列表
     */
    suspend fun updateCallContactorCache(uidList: List<String>) {
        withContext(Dispatchers.IO) {
            val toUpdate = uidList.filter { uid -> contactorCache.containsKey(uid) }
            toUpdate.forEach { uid ->
                callToChatController.getContactorById(application, uid).let { contactor ->
                    if (contactor.isPresent) {
                        contactorCache[contactor.get().id] = CacheEntry.Hit(contactor.get())
                    } else {
                        contactorCache[uid] = CacheEntry.Miss
                    }
                }
            }
        }
    }
    
    /**
     * 获取用户显示名称
     * 优先从缓存获取，缓存未命中时从数据库查询并更新缓存
     * 
     * @param id 用户ID（可能包含设备ID，格式：userId.deviceId）
     * @return 显示名称，如果未找到则返回 null
     */
    suspend fun getDisplayName(id: String?): String? {
        return withContext(Dispatchers.IO) {
            id?.let {
                val userId = it.split(".").firstOrNull() ?: it
                when (val entry = contactorCache[userId]) {
                    is CacheEntry.Hit -> {
                        L.d { "[Call] ContactorCacheManager DisplayName found in cache for userId: $userId, displayName: ${entry.contactor.getDisplayNameForUI()}" }
                        return@withContext entry.contactor.getDisplayNameForUI()
                    }
                    else -> {}
                }

                callToChatController.getContactorById(application, userId).let { contactor ->
                    if (contactor.isPresent) {
                        contactorCache[userId] = CacheEntry.Hit(contactor.get())
                        L.d { "[Call] ContactorCacheManager DisplayName retrieved for userId: $userId, displayName: ${contactor.get().getDisplayNameForUI()}" }
                        return@withContext contactor.get().getDisplayNameForUI()
                    } else {
                        contactorCache[userId] = CacheEntry.Miss
                    }
                }

                L.i { "[Call] ContactorCacheManager getDisplayName No displayName found for userId: $userId" }
            }
            return@withContext null
        }
    }
    
    /**
     * 通过ID获取显示名称
     * 如果找不到显示名称，则使用Base58格式的用户名作为后备
     * 
     * @param id 用户ID
     * @return 显示名称或Base58格式的用户名
     */
    suspend fun getDisplayNameById(id: String?): String? {
        return getDisplayName(id) ?: convertToBase58UserName(id)
    }
    
    /**
     * 获取群组显示名称
     * 
     * @param id 群组ID
     * @return 群组名称，如果未找到则返回 null
     */
    suspend fun getDisplayGroupNameById(id: String?): String? {
        if (id.isNullOrEmpty()) return null
        val group = callToChatController.getSingleGroupInfo(application, id)
        if (group.isPresent) {
            return group.get().name
        } else {
            L.i { "[Call] ContactorCacheManager getDisplayGroupNameById No group name found for id: $id" }
            return null
        }
    }
    
    /**
     * 通过UID获取用户头像
     * 优先从缓存获取，缓存未命中时从数据库查询并更新缓存
     * 
     * @param context 上下文
     * @param id 用户ID（可能包含设备ID）
     * @return 头像视图，如果未找到则返回 null
     */
    suspend fun getAvatarByUid(context: Context, id: String?): ConstraintLayout? {
        val userId = id?.split(".")?.firstOrNull() ?: return null
        val contactor = getContactorByUserId(userId)
        if (contactor == null) {
            L.i { "[ContactorCacheManager] getAvatarByUid No avatar found for provided id." }
            return null
        }
        return withContext(Dispatchers.Main) {
            callToChatController.getAvatarByContactor(context, contactor)
        }
    }
    
    /**
     * 根据名称或UID创建头像
     * 当无法获取用户信息时，使用名称或UID创建默认头像
     * 
     * @param context 上下文
     * @param name 用户名称（可选）
     * @param id 用户ID
     * @return 头像视图
     */
    fun createAvatarByNameOrUid(context: Context, name: String?, id: String): ConstraintLayout {
        return callToChatController.createAvatarByNameOrUid(context, name, id)
    }
    
    /**
     * 获取参与者显示信息
     * 包含用户ID、显示名称和头像
     * 
     * @param context 上下文
     * @param uid 用户ID
     * @return 参与者显示信息
     */
    suspend fun getParticipantDisplayInfo(context: Context, uid: String): CallUserDisplayInfo {
        val userId = uid.split(".").firstOrNull() ?: uid
        val contactor = getContactorByUserId(userId)
        val name = contactor?.getDisplayNameForUI() ?: getDisplayNameById(uid)
        val avatar = withContext(Dispatchers.Main) {
            contactor?.let { callToChatController.getAvatarByContactor(context, it) }
                ?: createAvatarByNameOrUid(context, name, userId)
        }
        return CallUserDisplayInfo(uid, name, avatar)
    }

    /**
     * 根据 userId 获取联系人信息（带本地缓存）
     *
     * 1. 优先从内存缓存中读取，避免重复调用
     * 2. 缓存 Miss 结果，防止对不存在的联系人反复查询
     * 3. 所有耗时操作统一切换到 IO 线程，避免阻塞主线程
     *
     * @param userId 用户唯一标识
     * @return ContactorModel，如果不存在则返回 null
     */
    private suspend fun getContactorByUserId(userId: String): ContactorModel? {
        return withContext(Dispatchers.IO) {
            val cached = when (val entry = contactorCache[userId]) {
                is CacheEntry.Hit -> entry.contactor
                else -> null
            }
            cached?.let { return@withContext it }
            callToChatController.getContactorById(application, userId).let { contactor ->
                if (contactor.isPresent) {
                    contactorCache[userId] = CacheEntry.Hit(contactor.get())
                    return@withContext contactor.get()
                } else {
                    contactorCache[userId] = CacheEntry.Miss
                }
            }
            return@withContext null
        }
    }
    
    /**
     * 获取关键提醒通知内容
     * 根据会话ID和来源ID生成通知标题和内容
     * 
     * @param conversationId 会话ID（可能是用户ID或群组ID）
     * @param sourceId 来源用户ID
     * @return Pair<标题, 内容>
     */
    suspend fun getCriticalAlertNotificationContent(
        conversationId: String,
        sourceId: String
    ): Pair<String, String> {
        val title = if (ValidatorUtil.isGid(conversationId)) {
            getDisplayGroupNameById(conversationId)
        } else {
            getDisplayNameById(conversationId)
        }
        val alertTitle = title ?: ResUtils.getString(R.string.notification_critical_alert_title_default)
        
        val callerName = getDisplayNameById(sourceId)
        val alertContent = callerName?.let {
            ResUtils.getString(R.string.notification_critical_alert_content_from, it)
        } ?: ResUtils.getString(R.string.notification_critical_alert_content_default)
        
        return alertTitle to alertContent
    }
    
    /**
     * 清空通讯录缓存
     * 在需要刷新缓存时调用
     */
    fun clearContactorCache() {
        contactorCache.clear()
    }
    
    /**
     * 将用户ID转换为Base58格式的用户名
     * 当无法获取显示名称时使用
     * 
     * @param identity 用户身份标识（可能包含设备ID）
     * @return Base58格式的用户名，如果转换失败则返回 null
     */
    private fun convertToBase58UserName(identity: String?): String? {
        val userId = identity?.split(".")?.firstOrNull() ?: return null
        if (!ValidatorUtil.isUid(userId)) {
            L.e { "[ContactorCacheManager] convertToBase58UserName error identity:$identity" }
            return null
        }
        return userId.formatBase58Id()
    }
}

