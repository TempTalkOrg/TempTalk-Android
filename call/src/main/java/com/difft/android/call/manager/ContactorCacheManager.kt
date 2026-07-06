package com.difft.android.call.manager

import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.call.LCallToChatController
import com.difft.android.call.R
import com.difft.android.call.data.AvatarData
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
class ContactorCacheManager @Inject constructor(
    private val lazyCallToChatController: dagger.Lazy<LCallToChatController>,
    @param:ApplicationContext private val context: Context,
) {
    private val callToChatController: LCallToChatController
        get() = lazyCallToChatController.get()

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
                val result = callToChatController.getContactorById(context, uid)
                if (result.isPresent) {
                    contactorCache[result.get().id] = CacheEntry.Hit(result.get())
                } else {
                    contactorCache[uid] = CacheEntry.Miss
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
                val cached = contactorCache[userId]
                if (cached is CacheEntry.Hit) return@withContext cached.contactor.getDisplayNameForUI()

                val result = callToChatController.getContactorById(context, userId)
                if (result.isPresent) {
                    contactorCache[userId] = CacheEntry.Hit(result.get())
                    return@withContext result.get().getDisplayNameForUI()
                } else {
                    contactorCache[userId] = CacheEntry.Miss
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
        val group = callToChatController.getSingleGroupInfo(id)
        if (group == null) {
            L.i { "[Call] ContactorCacheManager getDisplayGroupNameById No group name found for id: $id" }
        }
        return group?.name
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
     * 包含用户ID、显示名称和头像数据
     *
     * 整个方法体在 Dispatchers.IO 中执行，不再强制切回 Main 线程。
     * 返回 data-only 的 [AvatarData]；UI 层在 AndroidView factory 中按需在 Main 线程构建实际的 View。
     *
     * 注意：本方法不接受 Context 参数。原 context 参数在重构后已无内部使用，
     * 而调用方传入的 LocalContext.current（Activity-backed）会被 IO 协程捕获，
     * 形成潜在的 Activity 泄漏风险，因此显式移除。
     *
     * @param uid 用户ID
     * @return 参与者显示信息
     */
    suspend fun getParticipantDisplayInfo(uid: String): CallUserDisplayInfo =
        withContext(Dispatchers.IO) {
            val userId = uid.split(".").firstOrNull() ?: uid
            val contactor = getContactorByUserId(userId)
            val name = contactor?.getDisplayNameForUI() ?: getDisplayNameById(uid)
            val avatarData: AvatarData = if (contactor != null) {
                AvatarData.FromContactor(contactor)
            } else {
                AvatarData.FromNameOrUid(name, userId)
            }
            L.i { "[ContactorCacheManager] getParticipantDisplayInfo uid=$uid hasContactor=${contactor != null}" }
            CallUserDisplayInfo(uid, name, avatarData)
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
            val entry = contactorCache[userId]
            if (entry is CacheEntry.Hit) return@withContext entry.contactor
            val result = callToChatController.getContactorById(context, userId)
            if (result.isPresent) {
                contactorCache[userId] = CacheEntry.Hit(result.get())
                result.get()
            } else {
                contactorCache[userId] = CacheEntry.Miss
                null
            }
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
    
    // region Participant display observation

    private val _participantDisplayMap = MutableStateFlow<Map<String, CallUserDisplayInfo>>(emptyMap())
    val participantDisplayMap: StateFlow<Map<String, CallUserDisplayInfo>> = _participantDisplayMap

    private var contactsObserveJob: Job? = null

    /**
     * 启动通话参会者显示信息的集中监听。
     * 内部有 isActive 守卫，重复调用安全。通话结束时调用 [stopParticipantObservation]。
     */
    fun startParticipantObservation(scope: CoroutineScope) {
        if (contactsObserveJob?.isActive == true) return
        L.i { "[Call][ContactorCache] startParticipantObservation started" }
        contactsObserveJob = scope.launch(Dispatchers.IO) {
            callToChatController.getContactsUpdateListener().collect { updatedIds ->
                val currentMap = _participantDisplayMap.value
                val toUpdate = currentMap.keys.filter { uid ->
                    val userId = uid.split(".").firstOrNull() ?: uid
                    updatedIds.contains(userId)
                }
                if (toUpdate.isEmpty()) return@collect
                val participantUserIds = toUpdate.map { uid -> uid.split(".").firstOrNull() ?: uid }
                updateCallContactorCache(participantUserIds)
                val updated = toUpdate.associateWith { uid -> getParticipantDisplayInfo(uid) }
                _participantDisplayMap.update { it + updated }
            }
        }
    }

    /**
     * 加载单个参会者的显示信息并加入 [participantDisplayMap]。
     * 参会者首次出现时调用。
     */
    suspend fun loadParticipantDisplay(uid: String) {
        val info = getParticipantDisplayInfo(uid)
        _participantDisplayMap.update { it + (uid to info) }
    }

    /**
     * 通话结束，取消监听并清空参会者显示信息。
     */
    fun stopParticipantObservation() {
        L.i { "[Call][ContactorCache] stopParticipantObservation called" }
        contactsObserveJob?.cancel()
        contactsObserveJob = null
        _participantDisplayMap.value = emptyMap()
    }

    // endregion

    /**
     * 清空通讯录缓存
     * 在需要刷新缓存时调用
     */
    fun clearContactorCache() {
        contactorCache.clear()
    }

    /**
     * Precisely invalidate one user's cache entry (used by the weak-contact orchestration layer).
     * contactorCache is a ConcurrentHashMap, so remove is thread-safe and a no-op for an absent uid.
     */
    fun invalidateUser(uid: String) {
        contactorCache.remove(uid)
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

