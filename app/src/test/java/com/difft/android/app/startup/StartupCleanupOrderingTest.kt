package com.difft.android.app.startup

import android.app.Application
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

/**
 * Compile-time pin for the startup cleanup helpers wired into
 * `TempTalkApplication.onCreate` via `AppStartup.addNonBlocking`:
 * ```
 * .addNonBlocking(this::cleanupLegacySqlCipherArtifacts)
 * .addNonBlocking(this::sweepStaleSendingMessages)
 * .addNonBlocking { ApplicationDependencies.getJobManager().beginJobLoop() }
 * ```
 *
 * `addNonBlocking` launches each task independently on `Dispatchers.IO`
 * (`AppStartup.executeNonBlockingTasks`). There is NO ordering guarantee
 * between these tasks — they run in parallel. The sweep may therefore race
 * loosely against a user-initiated fresh `Sending` message via the UI path;
 * a misflagged fresh row is recoverable via the UI retry button
 * (`ChatMessageListFragment.kt` + `ChatMessageViewHolder.kt`).
 *
 * **Application override**: uses a plain `android.app.Application` as the
 * Robolectric test application so the real `TempTalkApplication.onCreate` is
 * NOT invoked — that chain needs Hilt + WCDB native libs which are unavailable
 * on the host JVM.
 *
 * Assertions: helpers are top-level `fun`s with the expected signature so any
 * refactor into an `object`/class or a parameter change breaks compilation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StartupCleanupOrderingTest {

    @Test
    fun `cleanupLegacySqlCipherArtifacts is a top-level fun taking Context`() {
        val ref: (Context) -> Unit = ::cleanupLegacySqlCipherArtifacts
        checkNotNull(ref)
    }

    @Test
    fun `sweepStaleSendingMessages is a top-level fun taking Application`() {
        val ref: (Application) -> Unit = ::sweepStaleSendingMessages
        checkNotNull(ref)
    }
}
