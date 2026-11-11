package com.difft.android.chat.messages

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.ApplicationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.CoroutineContext















class TestScopeApplication : ScopeApplication() {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Unconfined

    override fun onCreate() {
        super.onCreate()
        ApplicationHelper.init(this)

        
        
        val componentName = ComponentName(packageName, "$packageName.TestLauncherActivity")
        val pmShadow = shadowOf(packageManager)
        pmShadow.addActivityIfNotPresent(componentName)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MAIN)
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        pmShadow.addIntentFilterForActivity(componentName, filter)
    }
}
