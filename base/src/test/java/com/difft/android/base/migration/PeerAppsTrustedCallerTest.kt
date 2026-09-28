package com.difft.android.base.migration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `trustedCaller` is the entire security boundary of the exported migration-status provider: there
 * is no permission protecting it (the two package families are signed with different certificates,
 * so a signature-level permission could never be granted). Every ambiguity therefore has to fail
 * CLOSED, and each of those rows is pinned here — a future "it's probably the peer" shortcut would
 * leak the tri-state account status to any app on the device.
 *
 * Robolectric, because the function consults the real `android.os.Process.myUid()` to recognize a
 * self-call; the caller uid used below is deliberately a different value.
 */
@RunWith(RobolectricTestRunner::class)
class PeerAppsTrustedCallerTest {

    /** A context whose PackageManager resolves any uid to [packages] and can verify no certificate. */
    private fun callerContext(packageName: String, packages: Array<String>?): Context {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.getPackagesForUid(any()) } returns packages
        // No signing certificate is resolvable, so peerStatus can only answer NotInstalled — which
        // is what makes the "right package, unverifiable certificate" row below fail closed.
        every { pm.getPackageInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
        return mockk<Context>(relaxed = true).also {
            every { it.packageName } returns packageName
            every { it.packageManager } returns pm
            every { it.applicationInfo } returns ApplicationInfo()
        }
    }

    @Test
    fun `a uid that resolves to no package is refused`() {
        assertFalse(PeerApps.trustedCaller(callerContext(SELF, packages = null), CALLER_UID))
    }

    @Test
    fun `a uid that resolves to an empty package list is refused`() {
        assertFalse(PeerApps.trustedCaller(callerContext(SELF, packages = emptyArray()), CALLER_UID))
    }

    @Test
    fun `a shared uid is refused even when the peer is among its packages`() {
        // A shared uid means some OTHER package carries the caller's identity too, so the call
        // cannot be attributed to the peer. Ambiguity is refused rather than resolved in the
        // caller's favour.
        val context = callerContext(SELF, packages = arrayOf(PEER, "com.example.piggyback"))
        assertFalse(PeerApps.trustedCaller(context, CALLER_UID))
    }

    @Test
    fun `a package that is not the peer is refused`() {
        val context = callerContext(SELF, packages = arrayOf("com.example.other"))
        assertFalse(PeerApps.trustedCaller(context, CALLER_UID))
    }

    @Test
    fun `the peer package with an unverifiable signing certificate is refused`() {
        val context = callerContext(SELF, packages = arrayOf(PEER))
        assertFalse(PeerApps.trustedCaller(context, CALLER_UID))
    }

    private companion object {
        const val SELF = "org.difft.temptalk"
        const val PEER = "org.difft.chative"

        /** Any uid that is not this process's own, so the self-call shortcut cannot apply. */
        const val CALLER_UID = 10_123
    }
}
