package com.difft.android.app.startup

import android.app.Application
import com.difft.android.base.user.UserData
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the [needsIdentityKeyRelogin] pure predicate.
 *
 * Robolectric is used only so that L.i / L.e lazy lambdas don't fail on Timber/Log
 * access — the function under test itself is pure Kotlin.
 *
 * Covers the 4 branches required by design §3.9:
 *   (a) userData == null  → false
 *   (b) baseAuth empty    → false
 *   (c) both keys present → false
 *   (d) any one missing   → true  (3 sub-cases: public missing, private missing, both empty)
 *
 * The associated `cleanupLegacySqlCipherArtifacts` helper lives in
 * `StartupCleanup.kt` because `TempTalkApplication.onCreate` (not MainActivity)
 * drives the cleanup; its surface is pinned by [StartupCleanupOrderingTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StartupGuardTest {

    @Test
    fun `returns false when userData is null`() {
        assertFalse(needsIdentityKeyRelogin(null))
    }

    @Test
    fun `returns false when baseAuth is null`() {
        val data = UserData(
            baseAuth = null,
            aciIdentityPublicKey = "pk",
            aciIdentityPrivateKey = "sk"
        )
        assertFalse(needsIdentityKeyRelogin(data))
    }

    @Test
    fun `returns false when baseAuth is empty`() {
        val data = UserData(
            baseAuth = "",
            aciIdentityPublicKey = "pk",
            aciIdentityPrivateKey = "sk"
        )
        assertFalse(needsIdentityKeyRelogin(data))
    }

    @Test
    fun `returns false when both keys are present`() {
        val data = UserData(
            baseAuth = "auth-token",
            aciIdentityPublicKey = "pk",
            aciIdentityPrivateKey = "sk"
        )
        assertFalse(needsIdentityKeyRelogin(data))
    }

    @Test
    fun `returns true when public key is missing`() {
        val data = UserData(
            baseAuth = "auth-token",
            aciIdentityPublicKey = null,
            aciIdentityPrivateKey = "sk"
        )
        assertTrue(needsIdentityKeyRelogin(data))
    }

    @Test
    fun `returns true when private key is missing`() {
        val data = UserData(
            baseAuth = "auth-token",
            aciIdentityPublicKey = "pk",
            aciIdentityPrivateKey = null
        )
        assertTrue(needsIdentityKeyRelogin(data))
    }

    @Test
    fun `returns true when both keys are empty`() {
        val data = UserData(
            baseAuth = "auth-token",
            aciIdentityPublicKey = "",
            aciIdentityPrivateKey = ""
        )
        assertTrue(needsIdentityKeyRelogin(data))
    }
}
