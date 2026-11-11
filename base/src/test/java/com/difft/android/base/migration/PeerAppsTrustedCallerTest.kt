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











@RunWith(RobolectricTestRunner::class)
class PeerAppsTrustedCallerTest {

    
    private fun callerContext(packageName: String, packages: Array<String>?): Context {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.getPackagesForUid(any()) } returns packages
        
        
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

        
        const val CALLER_UID = 10_123
    }
}
