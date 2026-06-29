package com.difft.android.network.proxy

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.IConnectionRefresher
import com.difft.android.base.utils.IGlobalConfigsManager
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Protect IP address in calls" decoupling: the CALL plane must route through the
 * proxy ONLY when the proxy is active AND the protect toggle is ON, while the IM
 * plane stays governed by [ProxyConfigProvider.isEnabled] / [current] alone.
 *
 * Covers the call-plane views added for the feature: [currentForCall],
 * [isEnabledForCall], [isProtectCallIpEnabled], [setProtectCallIp], and the
 * companion mirrors [isProxyActiveForCall] / [isProxyForCallQuicEnabled] /
 * [isProxyForCallActiveWithoutQuic].
 */
@RunWith(RobolectricTestRunner::class)
class ProxyConfigProviderProtectCallTest {

    /** Minimal in-memory [UserManager] (same contract as the save-behavior test). */
    private class FakeUserManager : UserManager {
        private var current: UserData? = UserData()
        override fun setUserData(userData: UserData, commit: Boolean) {
            current = userData
        }
        override fun getUserData(): UserData? = current
    }

    private class NoopRefresher : IConnectionRefresher {
        override fun reconnectAfterProxyChange() = Unit
    }

    private fun makeProvider(): Pair<ProxyConfigProvider, FakeUserManager> {
        val gcm = mockk<IGlobalConfigsManager>(relaxed = true)
        every { gcm.getNewGlobalConfigs() } returns null
        val userManager = FakeUserManager()
        val provider = ProxyConfigProvider(
            userManager = userManager,
            globalConfigsManagerLazy = Lazy { gcm },
            connectionRefresherLazy = Lazy { NoopRefresher() },
        )
        return provider to userManager
    }

    /** Non-QUIC TURN-less link: enough to make the proxy "active" once enabled. */
    private val validLink: String = ProxyConfig(
        host = "1.2.3.4",
        port = 443,
        spkiPinBase64 = "pin",
    ).toShareLink()

    @Test
    fun `proxy on, protect off - call plane goes direct while IM plane tunnels`() {
        val (provider, _) = makeProvider()
        assertTrue(provider.save(validLink, enabled = true))

        // IM plane is active.
        assertTrue(provider.isEnabled)
        assertNotNull(provider.current)
        assertTrue(ProxyConfigProvider.isProxyActive)

        // Call plane is DIRECT (protect defaults OFF).
        assertFalse(provider.isProtectCallIpEnabled)
        assertNull(provider.currentForCall)
        assertFalse(provider.isEnabledForCall)
        assertFalse(ProxyConfigProvider.isProxyActiveForCall)
        assertFalse(ProxyConfigProvider.isProxyForCallQuicEnabled)
        assertFalse(ProxyConfigProvider.isProxyForCallActiveWithoutQuic)
    }

    @Test
    fun `proxy on, protect on - call plane joins the tunnel and persists`() {
        val (provider, userManager) = makeProvider()
        assertTrue(provider.save(validLink, enabled = true))
        provider.setProtectCallIp(true)

        assertTrue(provider.isProtectCallIpEnabled)
        assertNotNull(provider.currentForCall)
        assertEquals(provider.current?.toShareLink(), provider.currentForCall?.toShareLink())
        assertTrue(provider.isEnabledForCall)
        assertTrue(ProxyConfigProvider.isProxyActiveForCall)
        // Non-QUIC link → call plane is proxied but must force WSS (no QUIC relay).
        assertFalse(ProxyConfigProvider.isProxyForCallQuicEnabled)
        assertTrue(ProxyConfigProvider.isProxyForCallActiveWithoutQuic)

        // Intent is persisted to the encrypted store.
        assertTrue(userManager.getUserData()?.proxyProtectCallIp == true)
    }

    @Test
    fun `protect on but proxy off - call plane stays direct, flag retained`() {
        val (provider, _) = makeProvider()
        assertTrue(provider.save(validLink, enabled = true))
        provider.setProtectCallIp(true)
        assertTrue(ProxyConfigProvider.isProxyActiveForCall)

        // Disable the proxy (IM plane off) WITHOUT clearing the protect intent.
        assertTrue(provider.setEnabled(false))

        assertFalse(provider.isEnabled)
        assertTrue(provider.isProtectCallIpEnabled, "protect intent must be retained while proxy is off")
        assertNull(provider.currentForCall)
        assertFalse(provider.isEnabledForCall)
        assertFalse(ProxyConfigProvider.isProxyActiveForCall)
    }

    @Test
    fun `setProtectCallIp false reverts the call plane to direct`() {
        val (provider, userManager) = makeProvider()
        assertTrue(provider.save(validLink, enabled = true))
        provider.setProtectCallIp(true)
        assertTrue(ProxyConfigProvider.isProxyActiveForCall)

        provider.setProtectCallIp(false)

        assertFalse(provider.isProtectCallIpEnabled)
        assertNull(provider.currentForCall)
        assertFalse(provider.isEnabledForCall)
        assertFalse(ProxyConfigProvider.isProxyActiveForCall)
        assertFalse(userManager.getUserData()?.proxyProtectCallIp ?: false)
    }

    @Test
    fun `clear wipes the protect intent`() {
        val (provider, userManager) = makeProvider()
        assertTrue(provider.save(validLink, enabled = true))
        provider.setProtectCallIp(true)
        assertTrue(userManager.getUserData()?.proxyProtectCallIp == true)

        provider.clear()

        assertFalse(provider.isProtectCallIpEnabled)
        assertNull(provider.currentForCall)
        assertFalse(ProxyConfigProvider.isProxyActiveForCall)
        assertFalse(userManager.getUserData()?.proxyProtectCallIp ?: false)
    }
}
