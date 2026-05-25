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

/**
 * Test Application used by [MessageForegroundServiceIntegrationTest] to drive
 * `MessageForegroundService` lifecycle through `Robolectric.buildService`.
 *
 * Production code reached from the service requires:
 *  - `ApplicationHelper.instance` initialized (used by `ResUtils.getString`
 *    and `PackageUtil.getAppName`)
 *  - A launchable activity registered for `packageName` so the production
 *    code at `MessageForegroundService.kt:122`
 *    (`packageManager.getLaunchIntentForPackage(packageName)!!`) does not NPE
 *
 * This Application sets both up in `onCreate`, so the test only needs
 * `@Config(application = TestScopeApplication::class, sdk = [31])`.
 */
class TestScopeApplication : ScopeApplication() {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Unconfined

    override fun onCreate() {
        super.onCreate()
        ApplicationHelper.init(this)

        // Register a MAIN/LAUNCHER activity so packageManager.getLaunchIntentForPackage
        // returns a non-null Intent — required by createSettingsPendingIntent's `!!` deref.
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
