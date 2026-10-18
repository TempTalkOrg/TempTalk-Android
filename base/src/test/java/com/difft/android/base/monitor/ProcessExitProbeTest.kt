package com.difft.android.base.monitor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import kotlin.test.assertEquals

/** #1328: exit samples are collected for every reporting install, not only keep-alive-on ones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessExitProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private val reporter = mockk<KeepAliveHealthReporter>(relaxed = true)
    private val userManager = mockk<UserManager>()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        mockkStatic(FirebaseCrashlytics::class)
        every { FirebaseCrashlytics.getInstance() } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkAll()
    }

    private fun probeWith(
        keepAlive: Boolean?,
        reporting: Boolean,
        eligibilityFails: Boolean = false
    ): Pair<List<ExitSample>, Long?> = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("p.preferences_pb") })
        store.edit { it[AppStateKeys.LAST_SEEN_EXIT_TS] = 1L }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.packageName).setPid(1234).setTimestamp(5L)
                .setReason(ApplicationExitInfo.REASON_EXIT_SELF).setImportance(400).build()
        )
        every { userManager.getUserData() } returns keepAlive?.let { enabled ->
            mockk<UserData>(relaxed = true) { every { keepAliveEnabled } returns enabled }
        }
        if (keepAlive != null) {
            if (eligibilityFails) coEvery { reporter.isReporting(keepAlive) } throws IllegalStateException("boom")
            else coEvery { reporter.isReporting(keepAlive) } returns reporting
        }

        ProcessExitProbe(context, store, userManager, reporter).probe()

        val sent = mutableListOf<List<ExitSample>>()
        coVerify { reporter.onProbe(keepAlive, capture(sent)) }
        coVerify(exactly = if (keepAlive == null) 0 else 1) { reporter.isReporting(any()) }
        sent.single() to store.data.first()[AppStateKeys.LAST_SEEN_EXIT_TS]
    }

    @Test
    fun `a sampled control-group install collects its exit samples`() {
        val (samples, marker) = probeWith(keepAlive = false, reporting = true)
        assertEquals(1, samples.size)
        assertEquals(true, marker == 5L)
    }

    @Test
    fun `a non-reporting install collects nothing but still advances the dedup marker`() {
        val (samples, marker) = probeWith(keepAlive = false, reporting = false)
        assertEquals(emptyList(), samples)
        assertEquals(true, marker == 5L)
    }

    @Test
    fun `unknown keep-alive state still collects the exits for the reporter to stash`() {
        val (samples, marker) = probeWith(keepAlive = null, reporting = false)
        assertEquals(1, samples.size)
        assertEquals(5L, marker)
    }

    @Test
    fun `a failing eligibility check falls back to the keep-alive flag and never skips the exit scan`() {
        val (off, offMarker) = probeWith(keepAlive = false, reporting = true, eligibilityFails = true)
        assertEquals(emptyList(), off)
        assertEquals(5L, offMarker)
    }

    @Test
    fun `a failing eligibility check still collects when keep-alive is on`() {
        val (on, marker) = probeWith(keepAlive = true, reporting = false, eligibilityFails = true)
        assertEquals(1, on.size)
        assertEquals(5L, marker)
    }
}
