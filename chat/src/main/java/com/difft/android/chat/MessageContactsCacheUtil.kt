package com.difft.android.chat

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.wcdb
import java.util.concurrent.ConcurrentHashMap

/**
 * 消息联系人缓存工具类（页面级）
 *
 * **重要：这是一个实例类，应该在 ViewModel 中创建实例，跟随页面生命周期自动释放**
 *
 * 功能：
 * 1. 为消息列表渲染提供统一的联系人信息来源
 * 2. 从消息列表中收集需要的联系人ID
 * 3. 批量预加载联系人到缓存，避免在ViewHolder中进行数据库查询
 *
 * 使用流程：
 * 1. 在ViewModel中创建实例：`val contactsCache = MessageContactsCacheUtil()`
 * 2. 使用 collectContactIds 收集所有需要的联系人ID
 * 3. 调用 loadContactors 批量加载到缓存
 * 4. 在ViewHolder中通过 getContactor 直接读取（同步，零查询）
 * 5. ViewModel销毁时，缓存自动释放（无需手动清理）
 *
 * **线程安全：**
 * - 使用 ConcurrentHashMap 保证多线程访问安全
 * - 主线程（ViewHolder）读取 + IO线程（ViewModel协程）写入场景
 */
class MessageContactsCacheUtil {

    private val cache = ConcurrentHashMap<String, ContactorModel>()

    /**
     * 同步获取联系人信息
     *
     * 注意：必须先调用 loadContactors 加载数据到缓存
     *
     * @param id 联系人ID
     * @return 联系人信息，如果缓存中没有返回null
     */
    fun getContactor(id: String): ContactorModel? {
        return cache[id]
    }

    /**
     * 批量加载联系人信息并更新缓存
     *
     * 只查询缓存中不存在的联系人，避免重复查询
     *
     * @param ids 需要加载的联系人ID集合
     */
    suspend fun loadContactors(ids: Set<String>) {
        if (ids.isEmpty()) return

        // 过滤掉已在缓存中的ID
        val missingIds = ids.filter { cache[it] == null }.toSet()
        if (missingIds.isEmpty()) {
            L.d { "MessageContactsCacheUtil: All ${ids.size} contactors already in cache" }
            return
        }

        L.d { "MessageContactsCacheUtil: Loading ${missingIds.size} contactors (${ids.size - missingIds.size} already cached)" }

        // 批量查询数据库
        val contactors = withContext(Dispatchers.IO) {
            wcdb.getContactorsFromAllTable(missingIds.toList())
        }

        // 更新缓存
        contactors.forEach { contactor ->
            cache[contactor.id] = contactor
        }

        L.i { "MessageContactsCacheUtil: Loaded ${contactors.size} contactors, total cache size: ${cache.size}" }
    }

    /**
     * 直接插入或更新联系人缓存
     *
     * 用于联系人信息更新时直接塞入新数据
     *
     * @param contactor 联系人信息
     */
    fun put(contactor: ContactorModel) {
        cache[contactor.id] = contactor
        L.d { "MessageContactsCacheUtil: Put contactor ${contactor.id} into cache" }
    }

    /**
     * 移除指定联系人的缓存
     *
     * 用于联系人信息更新时清除过期缓存
     *
     * @param id 联系人ID
     */
    fun remove(id: String) {
        cache.remove(id)
        L.d { "MessageContactsCacheUtil: Removed contactor $id from cache" }
    }

    /**
     * 批量移除联系人缓存
     *
     * @param ids 联系人ID集合
     */
    fun remove(ids: Set<String>) {
        ids.forEach { cache.remove(it) }
        L.d { "MessageContactsCacheUtil: Removed ${ids.size} contactors from cache" }
    }

    /**
     * 清空缓存
     *
     * 页面销毁时自动调用（通过ViewModel.onCleared），无需手动调用
     */
    fun clear() {
        cache.clear()
        L.d { "MessageContactsCacheUtil: Cache cleared" }
    }

    /**
     * 获取缓存中的所有联系人ID
     *
     * 用于检查联系人更新时是否需要刷新
     *
     * @return 缓存中所有联系人的ID集合（快照副本）
     */
    fun getCachedIds(): Set<String> {
        return cache.keys.toSet()
    }

    /**
     * 获取缓存大小
     *
     * @return 缓存中联系人的数量
     */
    fun size(): Int {
        return cache.size
    }

    companion object {
        /**
         * 从ChatMessage列表中收集所有需要的联系人ID
         *
         * 收集范围：
         * 1. 消息发送者 (authorId)
         * 2. Reaction中的用户 (reaction.uid)
         * 3. 转发消息中的作者 (forward.author)
         * 4. 联系人卡片 (sharedContacts)
         * 5. Quote引用的作者 (quote.author)
         *
         * @param messages ChatMessage列表
         * @return 所有需要的联系人ID集合（去重）
         */
        fun collectContactIds(messages: List<ChatMessage>): Set<String> {
            return buildSet {
                messages.forEach { msg ->
                    // 1. 消息发送者
                    add(msg.authorId)

                    if (msg is TextChatMessage) {
                        // 2. Reaction中的联系人
                        msg.reactions?.forEach { reaction ->
                            add(reaction.uid)
                        }

                        // 3. 转发消息中的作者
                        msg.forwardContext?.forwards?.forEach { forward ->
                            add(forward.author)
                        }

                        // 4. 联系人卡片
                        msg.sharedContacts?.forEach { contact ->
                            contact.phone?.firstOrNull()?.value?.let { phoneId ->
                                add(phoneId)
                            }
                        }

                        // 5. Quote引用的作者
                        msg.quote?.author?.let { authorId ->
                            add(authorId)
                        }
                    }
                }
            }
        }
    }
}
