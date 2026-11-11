package com.difft.android.base.utils

import android.content.Context
import android.content.SharedPreferences
import android.view.Window
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


object StatusBarSetting {
    private const val PREF_NAME = "status_bar_prefs"
    private const val KEY_HIDDEN = "hidden"

    @Volatile
    private var cached: Boolean? = null
    private val mutableHidden = MutableStateFlow(false)
    val hidden: StateFlow<Boolean> = mutableHidden.asStateFlow()
    private val windows = Collections.newSetFromMap(WeakHashMap<Window, Boolean>())

    @VisibleForTesting
    internal var prefsProvider: (Context) -> SharedPreferences = { it.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }

    fun isHidden(context: Context): Boolean {
        cached?.let { return it }
        return prefsProvider(context).getBoolean(KEY_HIDDEN, false).also {
            cached = it
            mutableHidden.value = it
        }
    }

    @MainThread
    fun setHidden(context: Context, hidden: Boolean) {
        prefsProvider(context).edit().putBoolean(KEY_HIDDEN, hidden).commit()
        cached = hidden
        mutableHidden.value = hidden
        val liveWindows = windows.toList()
        L.i { "[StatusBar] setting hidden=$hidden windows=${liveWindows.size}" }
        liveWindows.forEach { apply(context, it) }
    }

    @MainThread
    fun attach(context: Context, window: Window) {
        windows.add(window)
        apply(context, window)
    }

    @MainThread
    fun detach(window: Window) {
        windows.remove(window)
    }

    @MainThread
    fun apply(context: Context, window: Window) {
        if (isHidden(context)) window.hideStatusBar() else window.showStatusBar()
    }

    @VisibleForTesting
    internal fun resetForTest() {
        cached = null
        mutableHidden.value = false
        windows.clear()
    }
}
