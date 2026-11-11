package com.difft.android.base.widget

import android.app.Activity
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.WeakHashMap







object DialogAnchor {
    private val centres = MutableStateFlow<Map<Activity, Int>>(emptyMap())

    
    fun set(activity: Activity, centerYPx: Int?) {
        centres.value = WeakHashMap(centres.value).apply {
            if (centerYPx == null) remove(activity) else put(activity, centerYPx)
        }
    }

    



    @Composable
    internal fun Effect() {
        val view = LocalView.current
        val activity = LocalContext.current.getSafeActivity()
        val all by centres.collectAsState()
        val centerY = activity?.let { all[it] }
        LaunchedEffect(centerY) {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
            val windowHeight = activity?.window?.decorView?.height ?: 0
            val params = window.attributes
            params.gravity = Gravity.CENTER
            params.y = centerY?.takeIf { windowHeight > 0 }?.let { it - windowHeight / 2 } ?: 0
            window.attributes = params
        }
    }
}
