package com.difft.android.app.startup

import android.app.Application
import com.difft.android.base.user.UserData
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue






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
