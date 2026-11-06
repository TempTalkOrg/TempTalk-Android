package com.difft.android.test

import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.ApplicationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/** Real application contract for production dimensions and page-owned work in Hilt tests. */
open class ScopeTestApplication : ScopeApplication() {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Main
    override fun onCreate() {
        super.onCreate()
        ApplicationHelper.init(this)
    }
    override fun onTerminate() {
        coroutineContext.cancel()
        super.onTerminate()
    }
}
