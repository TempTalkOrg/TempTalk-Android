package com.difft.android.base.user

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

object AppLockCallbackManager {

    private val listeners = ConcurrentHashMap<String, WeakReference<(Boolean) -> Unit>>()

    /** 注册解锁回调 */
    fun addListener(id: String, listener: (Boolean) -> Unit) {
        listeners[id] = WeakReference(listener)
    }

    /** 移除监听 */
    fun removeListener(id: String) {
        listeners.remove(id)
    }

    /** 通知所有监听者：解锁成功 */
    fun notifyUnlockSuccess() {
        // Step 1: 清理掉所有 WeakReference 已被 GC 的条目
        listeners.entries.removeIf { it.value.get() == null }
        // Step 2: 捕获 snapshot（无竞态，因为无效项已被清理）
        val callbacks = listeners.values.mapNotNull { it.get() }
        // Step 3: 执行回调（不会执行过期对象）
        callbacks.forEach { it.invoke(true) }
    }
}