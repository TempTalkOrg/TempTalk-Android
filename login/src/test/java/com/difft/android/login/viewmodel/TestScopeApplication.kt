package com.difft.android.login.viewmodel

import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.ApplicationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * Robolectric test [ScopeApplication] for [LoginViewModelMigrationTest].
 *
 * The migration-prompt tests that exercise `continueSignInAfterPrompt` reach the existing
 * [LoginViewModel.signIn], which launches a `Dispatchers.Default` coroutine. That coroutine touches
 * the global `application` delegate ([ApplicationHelper.instance]); with a plain `Application` it is
 * uninitialized and the escaping coroutine throws, leaking into the NEXT test as
 * `UncaughtExceptionsBeforeTest`. Initializing [ApplicationHelper] here makes that access succeed so
 * the suite is order-independent. Mirrors the `:chat` / `:call` test-application pattern.
 */
class TestScopeApplication : ScopeApplication() {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Unconfined

    override fun onCreate() {
        super.onCreate()
        ApplicationHelper.init(this)
    }
}
