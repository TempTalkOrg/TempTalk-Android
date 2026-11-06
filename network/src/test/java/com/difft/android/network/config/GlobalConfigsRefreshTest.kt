package com.difft.android.network.config

import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.responses.EncryptedGlobalConfigResponse
import com.google.gson.Gson
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GlobalConfigsManager]'s refresh: only a fetch that adopted a config stamps the throttle, so a
 * failed round is retried on the next state-change signal instead of a full interval later
 * (#1395). The fake [HttpService] serves both the plaintext and the encrypted endpoint, so the
 * test holds whichever config mode the build was compiled with.
 */
class GlobalConfigsRefreshTest {

    /** Response per request in order: a config code, or null for a network failure. */
    private val script = ArrayDeque<Int?>()
    private var requests = 0
    private var monoNow = 1_000_000L

    private val httpService = mockk<HttpService> {
        coEvery { getGlobalConfig(any()) } answers { NewGlobalConfig(code = nextCode()) }
        coEvery { getEncryptedGlobalConfig(any()) } answers { EncryptedGlobalConfigResponse(code = nextCode()) }
    }
    private val client = mockk<ChativeHttpClient> { every { httpService } returns this@GlobalConfigsRefreshTest.httpService }

    private val manager = GlobalConfigsManager(
        context = mockk(relaxed = true),
        httpClient2 = Lazy { client },
        environmentHelper = mockk<EnvironmentHelper> {
            every { ENVIRONMENT_DEVELOPMENT } returns "development"
            every { isThatEnvironment(any()) } returns false
        },
        userManager = mockk(relaxed = true),
        chatHttpClient = Lazy { client },
        secureConfigStore = mockk(relaxed = true),
        gson = Gson(),
    ).also { it.refreshGate = RefreshGate { monoNow } }

    @Before
    fun setUp() {
        mockkObject(GlobalConfigCrypto)
        every { GlobalConfigCrypto.decryptGlobalConfig(any()) } returns "{}"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun nextCode(): Int {
        requests++
        return script.removeFirstOrNull() ?: throw IOException("offline")
    }

    @Test
    fun `all URLs failing returns false and leaves the throttle unstamped`() = runTest {
        assertFalse(manager.fetchGlobalConfigsWithRetry())
        assertEquals(manager.globalConfigUrls.size, requests)

        manager.doRefreshIfNeeded()

        assertEquals(0L, manager.refreshGate.lastRefreshTime)
        assertTrue(manager.refreshGate.isDue(FOREGROUND_MS))
    }

    @Test
    fun `after a failed round the next signal fetches again at once`() = runTest {
        manager.doRefreshIfNeeded()
        val afterFirst = requests
        monoNow += 1_000L

        manager.doRefreshIfNeeded()

        assertTrue(afterFirst > 0)
        assertEquals(afterFirst * 2, requests)
    }

    @Test
    fun `a failing URL followed by a good one returns true and stamps`() = runTest {
        assumeTrue("needs a fallback URL", manager.globalConfigUrls.size >= 2)
        script.addAll(listOf(1, 0))

        manager.doRefreshIfNeeded()

        assertEquals(2, requests)
        assertEquals(monoNow, manager.refreshGate.lastRefreshTime)
        monoNow += 1_000L
        manager.doRefreshIfNeeded()
        assertEquals(2, requests, "a stamped gate throttles the next signal")
    }

    private companion object {
        const val FOREGROUND_MS = 5 * 60 * 1000L
    }
}
