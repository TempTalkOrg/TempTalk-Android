package com.difft.android.migration.v2.handoff

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.migration.v2.model.NeutralCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test











class CredentialInstallerTest {

    private class FakeUserManager(var data: UserData = UserData()) : UserManager {
        var commits = 0
        override fun setUserData(userData: UserData, commit: Boolean) {
            data = userData
            if (commit) commits++
        }

        override fun getUserData(): UserData = data
    }

    private val credential = NeutralCredential(
        account = "+12025550100",
        baseAuth = "base-auth",
        microToken = "micro-token",
        signalingKey = "signaling-key",
        email = "a@b.c",
        phoneNumber = "+12025550100",
        handoffId = "handoff-1",
    )

    @Test
    fun `an account-less install adopts the credential in a single committed write`() {
        val userManager = FakeUserManager()
        val result = CredentialInstaller(userManager).install(credential)

        assertEquals(CredentialInstaller.Result.Installed, result)
        assertEquals(credential.account, userManager.data.account)
        assertEquals(credential.baseAuth, userManager.data.baseAuth)
        assertEquals(credential.microToken, userManager.data.microToken)
        assertEquals(credential.signalingKey, userManager.data.signalingKey)
        assertEquals(credential.email, userManager.data.email)
        assertEquals(credential.phoneNumber, userManager.data.phoneNumber)
        assertEquals(credential.handoffId, userManager.data.migrationHandoffCompletedId)
        assertEquals(1, userManager.commits)
    }

    @Test
    fun `an account that appeared during promote is never overwritten`() {
        
        val userManager = FakeUserManager(UserData(account = "+12025559999", baseAuth = "other-auth"))

        val result = CredentialInstaller(userManager).install(credential)

        assertEquals(CredentialInstaller.Result.SkippedNotFresh, result)
        
        assertEquals("+12025559999", userManager.data.account)
        assertEquals("other-auth", userManager.data.baseAuth)
        assertNull(userManager.data.microToken)
        assertEquals("", userManager.data.migrationHandoffCompletedId)
        assertEquals(0, userManager.commits)
    }

    @Test
    fun `the same credential already on disk reads as installed and writes nothing`() {
        val userManager = FakeUserManager(
            UserData(account = credential.account, migrationHandoffCompletedId = credential.handoffId),
        )

        val result = CredentialInstaller(userManager).install(credential)

        assertEquals(CredentialInstaller.Result.AlreadyInstalled, result)
        assertEquals(0, userManager.commits)
    }

    @Test
    fun `the credential never carries app lock material`() {
        val userManager = FakeUserManager()
        CredentialInstaller(userManager).install(credential)
        
        
        assertTrue(userManager.data.passcode.isNullOrEmpty())
        assertTrue(userManager.data.pattern.isNullOrEmpty())
    }
}
