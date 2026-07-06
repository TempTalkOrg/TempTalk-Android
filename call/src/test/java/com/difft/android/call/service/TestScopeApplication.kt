package com.difft.android.call.service

import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.ApplicationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * Test Application used by [ForegroundServiceIntegrationTest] to drive
 * `ForegroundService` lifecycle through `Robolectric.buildService`.
 *
 * `ForegroundService.buildForegroundNotification()` calls `PackageUtil.getAppName()`
 * which dereferences `ApplicationHelper.instance` (lateinit). This Application
 * initializes it in `onCreate` so the lateinit is set before any service
 * lifecycle methods run.
 *
 * Apply via `@Config(application = TestScopeApplication::class, sdk = [31])`.
 */
class TestScopeApplication : ScopeApplication() {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Unconfined

    override fun onCreate() {
        super.onCreate()
        ApplicationHelper.init(this)
    }
}
