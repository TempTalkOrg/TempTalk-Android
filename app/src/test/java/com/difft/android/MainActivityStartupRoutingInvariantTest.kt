package com.difft.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1395 adds a warm-process foreground gate ([com.difft.android.app.WarmProcessResync]) that
 * reuses MainActivity's two session checks without changing MainActivity's routing. This pins
 * that routing in the source: the local-token check and the identity-key check stay separate
 * steps, and a logged-in user with missing identity keys is still logged out passively, after
 * every not-logged-in branch. Source text because the routing runs inside `lifecycleScope` behind
 * the database-health gate, which a unit test cannot reach.
 */
class MainActivityStartupRoutingInvariantTest {

    private val source = File("src/main/java/com/difft/android/MainActivity.kt").readText()

    @Test
    fun `the local-token check and the identity-key check stay separate steps`() {
        assertTrue(source.contains("val loggedIn = verifyLocalToken()"))
        assertTrue(source.contains("needsIdentityKeyRelogin(userManager.getUserData())"))
        assertTrue(source.contains("private fun verifyLocalToken(): Boolean = LocalTokenVerifier.isLocalTokenValid("))
    }

    @Test
    fun `missing identity keys still route to the passive logout after the not-logged-in branches`() {
        val identityBranch = source.indexOf("state.needsIdentityRelogin ->")
        assertTrue("identity branch present", identityBranch >= 0)
        val logout = source.indexOf("logoutManager.doLogoutWithoutRemoveData()", identityBranch)
        val nextBranch = source.indexOf("->", source.indexOf('\n', identityBranch))
        assertTrue("the identity branch logs out", logout in identityBranch until nextBranch)
        val lastNotLoggedIn = source.lastIndexOf("!state.isLoggedIn")
        assertTrue("after every not-logged-in branch", lastNotLoggedIn in 0 until identityBranch)
    }
}
