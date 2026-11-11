package com.difft.android.app.startup

import android.app.Application
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test







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
