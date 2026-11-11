package com.difft.android.startup.prompt

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton


data class PromptSession(val id: Long, val active: Boolean)








@Singleton
class PromptSessionTracker @Inject constructor() : Application.ActivityLifecycleCallbacks {

    private var started = 0
    private var pendingRelaunches = 0

    private val _session = MutableStateFlow(PromptSession(id = 0, active = false))
    val session: StateFlow<PromptSession> = _session.asStateFlow()

    override fun onActivityStarted(activity: Activity) {
        if (pendingRelaunches > 0) {
            pendingRelaunches--
            return
        }
        started++
        if (started == 1) _session.value = PromptSession(_session.value.id + 1, active = true)
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity.isChangingConfigurations) {
            pendingRelaunches++
            return
        }
        started--
        if (started <= 0) {
            started = 0
            
            pendingRelaunches = 0
            _session.value = _session.value.copy(active = false)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
