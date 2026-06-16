package com.difft.android.call.manager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.storage.SecureConfigStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.proxy.ProxyConfigProvider
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T16 (design §10) — cold-start assets-fallback path of
 * [CallServiceUrlManager.getCachedServiceUrlsDomains].
 *
 * Lives in `:call`'s test source set because `:network`'s test classpath does
 * not include `:call`. `:call` is a transitive-only dependency of `:network`
 * via the runtime Hilt graph, NOT a `testImplementation` dependency of
 * `:network` — verified in `network/build.gradle.kts`.
 *
 * Robolectric is required: the test calls `appContext.assets.open(...)` via
 * `DefaultGlobalConfigCallServiceUrlsReader.read`. The test fixture lives at
 * `call/src/test/assets/default_global_config.json` and is exposed at runtime
 * because `call/build.gradle.kts` sets `unitTests.isIncludeAndroidResources = true`.
 */
// Pin the Robolectric SDK like the other :call Robolectric tests: the default
// (project targetSdk 36) needs JDK 21, but the build runs on JDK 17, which fails
// sandbox creation ("Android SDK 36 requires Java 21"). 33 matches the repo-wide
// robolectric.properties default and the sibling ContactorCacheManagerTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CallServiceUrlManagerAssetsFallbackTest {

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `T16 getCachedServiceUrlsDomains returns assets primary plus fallback domains when memState null`() {
        // Arrange — fresh manager: memState = null (no successful fetch yet),
        // SecureConfigStore returns an empty disk state (so loadFromDiskLocked
        // would keep memState null even if it were called — but this code path
        // does NOT call loadFromDiskLocked per the C1 invariant).
        val secureConfigStore = mockk<SecureConfigStore>(relaxed = true)
        every { secureConfigStore.callServiceUrlStateV3Flow } returns flowOf("")
        val httpClient = mockk<ChativeHttpClient>(relaxed = true)
        val proxyProvider = mockk<ProxyConfigProvider>(relaxed = true)

        val manager = CallServiceUrlManager(
            appContext = appContext,
            callHttpClient = Lazy { httpClient },
            secureConfigStore = secureConfigStore,
            proxyConfigProviderLazy = Lazy { proxyProvider },
        )

        // Act — first call parses the bundled `default_global_config.json` via
        // DefaultGlobalConfigCallServiceUrlsReader.
        val first = manager.getCachedServiceUrlsDomains()

        // Assert — primary + fallback domains come through, normalized to
        // lowercase with no trailing dots. IP addresses are NEVER included.
        assertNotNull(first)
        assertEquals(
            listOf("test-primary.ablivekit.org", "test-fallback.ablivekit.org"),
            first,
        )
        assertTrue(first.none { it.any { ch -> ch.isUpperCase() } }, "all entries must be lowercase")
        assertTrue(first.none { it.contains(':') }, "no port:host in assets fallback")
        assertTrue(first.none { it.matches(Regex("""^\d+\.\d+\.\d+\.\d+$""")) }, "no IPv4 addresses")

        // Second call — should hit the cached `assetsFallbackDomains` and return
        // the SAME instance (or at least the same content) without reparsing.
        val second = manager.getCachedServiceUrlsDomains()
        assertEquals(first, second)
    }
}
