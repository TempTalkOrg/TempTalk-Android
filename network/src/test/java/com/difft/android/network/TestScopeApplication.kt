package com.difft.android.network

import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.ApplicationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * Test Application for interceptor tests: they dereference the `application` global (for
 * `Accept-Language` and the error string), which is a lateinit set at process start in production.
 *
 * Apply via `@Config(application = TestScopeApplication::class)`.
 */
class TestScopeApplication : ScopeApplication() {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Unconfined

    override fun onCreate() {
        super.onCreate()
        ApplicationHelper.init(this)
    }
}
