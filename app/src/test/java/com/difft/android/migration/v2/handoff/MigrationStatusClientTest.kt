package com.difft.android.migration.v2.handoff

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import com.difft.android.base.migration.PeerApps
import android.os.Bundle
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * How the client tells "a peer from before the same-device migration existed" apart from "no peer":
 * an installed, trusted peer that registers no status provider is TooOld — every release of either
 * family before 2.4.4 — and is decided through the package manager, before any provider call. The
 * provider itself is unreachable in this JVM, so every path that goes on to call it ends Unavailable.
 */
class MigrationStatusClientTest {

    private val peer = "org.difft.chative"
    private val authority = "$peer.migration.status"
    private val packageManager = mockk<PackageManager>()
    private val context = mockk<Context>(relaxed = true) {
        every { packageManager } returns this@MigrationStatusClientTest.packageManager
    }
    private val client = MigrationStatusClient(context)

    @Before
    fun mockPeer() {
        mockkObject(PeerApps)
        every { PeerApps.peerPackage(context) } returns peer
        every { PeerApps.peerStatusAuthority(context) } returns authority
        // The query bundle is built outside the guarded call; the android.jar stub would throw.
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().putString(any(), any()) } just Runs
    }

    @After
    fun unmock() {
        unmockkObject(PeerApps)
        unmockkConstructor(Bundle::class)
    }

    private fun providerOwnedBy(owner: String?) {
        every { packageManager.resolveContentProvider(authority, 0) } returns
            owner?.let { mockk<ProviderInfo>().apply { packageName = it } }
    }

    @Test
    fun `an installed, trusted peer with no status provider is too old, decided before any call`() = runTest {
        every { PeerApps.peerStatus(context) } returns PeerApps.PeerStatus.Trusted
        providerOwnedBy(null)

        assertEquals(MigrationStatusClient.PeerStatusReply.TooOld, client.queryStatus())
        verify(exactly = 0) { context.contentResolver }
    }

    @Test
    fun `the authority held by some other package counts as the peer shipping none`() = runTest {
        every { PeerApps.peerStatus(context) } returns PeerApps.PeerStatus.Trusted
        providerOwnedBy("com.example.squatter")

        assertEquals(MigrationStatusClient.PeerStatusReply.TooOld, client.queryStatus())
    }

    @Test
    fun `a trusted peer that registers the provider is asked, never presumed old`() = runTest {
        every { PeerApps.peerStatus(context) } returns PeerApps.PeerStatus.Trusted
        providerOwnedBy(peer)

        assertEquals(MigrationStatusClient.PeerStatusReply.Unavailable, client.queryStatus())
    }

    @Test
    fun `no peer, or an untrusted one, is unavailable rather than too old`() = runTest {
        every { PeerApps.peerStatus(context) } returns PeerApps.PeerStatus.NotInstalled
        assertEquals(MigrationStatusClient.PeerStatusReply.Unavailable, client.queryStatus())

        every { PeerApps.peerStatus(context) } returns PeerApps.PeerStatus.Untrusted(listOf("deadbeef"))
        assertEquals(MigrationStatusClient.PeerStatusReply.Unavailable, client.queryStatus())
        verify(exactly = 0) { packageManager.resolveContentProvider(any(), any<Int>()) }
    }
}
