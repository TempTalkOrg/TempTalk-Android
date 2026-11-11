package com.difft.android.base.user

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

object AppLockCallbackManager {

    private val listeners = ConcurrentHashMap<String, WeakReference<(Boolean) -> Unit>>()

    fun addListener(id: String, listener: (Boolean) -> Unit) {
        listeners[id] = WeakReference(listener)
    }

    fun removeListener(id: String) {
        listeners.remove(id)
    }

    fun notifyUnlockSuccess() {
        listeners.entries.removeIf { it.value.get() == null }
        val callbacks = listeners.values.mapNotNull { it.get() }
        callbacks.forEach { it.invoke(true) }
    }
}