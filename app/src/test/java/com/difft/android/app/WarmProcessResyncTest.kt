package com.difft.android.app

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.push.PushUtil
import com.difft.android.push.ResyncResult
import com.difft.android.test.builders.buildUserData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [WarmProcessResync] (#1395): on foreground it starts the archive loop and retries the FCM bind,
 * but only when the database passed this process's health probe, is accessible, and the stored
 * session passes the same two checks MainActivity applies (local token, identity keys). A closed
 * gate runs nothing and logs its reason once per process; an unfinished probe is waited for,
 * bounded at 10 s. One task failing never skips the other. Robolectric because the session check
 * decodes the JWT through `android.util.Base64`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WarmProcessResyncTest {

    private var user: UserData? = sessionUser()
    private var token: String? = jwtFor(ACCOUNT)
    private var inaccessible = false
    private var probed = true
    private val probeFinished = MutableStateFlow(true)

    private val wcdb = mockk<WCDB> {
        every { isDbInaccessible } answers { inaccessible }
        every { isHealthyProbed } answers { probed }
        every { probeFinished } returns this@WarmProcessResyncTest.probeFinished
    }
    private val archive = mockk<MessageArchiveManager> { every { startCheckTask() } returns true }
    private val push = mockk<PushUtil> { coEvery { resyncBind() } returns ResyncResult.OK }
    private val resync = WarmProcessResync(
        userManager = mockk<UserManager> { every { getUserData() } answers { user } },
        wcdb = wcdb,
        authTokenProvider = mockk<AuthTokenProvider> { every { peek() } answers { token } },
        messageArchiveManager = archive,
        pushUtil = push,
    )

    @Test
    fun `an open gate runs both tasks`() = runTest {
        resync.onForeground()

        verify(exactly = 1) { archive.startCheckTask() }
        coVerify(exactly = 1) { push.resyncBind() }
    }

    @Test
    fun `each closed gate skips every task, logs its reason once, and the next open foreground runs`() {
        val closers = listOf<Pair<String, () -> Unit>>(
            "probe" to { probed = false },
            "inaccessible" to { inaccessible = true },
            "session" to { token = jwtFor("+19990000000") },
            "session" to { user = buildUserData(account = ACCOUNT) },
            "session" to { user = null },
        )
        val logs = captureLogs {
            closers.forEach { (_, close) ->
                close()
                runTest {
                    resync.onForeground()
                    resync.onForeground()
                }
                verify(exactly = 0) { archive.startCheckTask() }
                coVerify(exactly = 0) { push.resyncBind() }
                reopen()
            }
        }

        listOf("probe", "inaccessible", "session").forEach { reason ->
            assertEquals("reason=$reason logged once", 1, logs.count { it.contains("gate closed reason=$reason") })
        }

        runTest { resync.onForeground() }
        verify(exactly = 1) { archive.startCheckTask() }
        coVerify(exactly = 1) { push.resyncBind() }
    }

    @Test
    fun `a failing task does not skip the other`() = runTest {
        every { archive.startCheckTask() } throws IllegalStateException("db")

        resync.onForeground()

        coVerify(exactly = 1) { push.resyncBind() }
    }

    @Test
    fun `cancellation inside a task propagates`() = runTest {
        coEvery { push.resyncBind() } throws CancellationException("stopped")

        val error = runCatching { resync.onForeground() }.exceptionOrNull()

        assertTrue(error is CancellationException)
        verify(exactly = 1) { archive.startCheckTask() }
    }

    @Test
    fun `an unfinished probe that passes within 10 s is waited for`() = runTest {
        probed = false
        probeFinished.value = false
        val run = async { resync.onForeground() }
        advanceTimeBy(3_000L)
        runCurrent()
        verify(exactly = 0) { archive.startCheckTask() }

        probed = true
        probeFinished.value = true
        run.await()

        verify(exactly = 1) { archive.startCheckTask() }
        coVerify(exactly = 1) { push.resyncBind() }
    }

    @Test
    fun `an unfinished probe that does not finish within 10 s skips`() {
        probed = false
        probeFinished.value = false
        val logs = captureLogs {
            runTest {
                resync.onForeground()
                assertEquals(10_000L, currentTime)
            }
        }

        verify(exactly = 0) { archive.startCheckTask() }
        coVerify(exactly = 0) { push.resyncBind() }
        assertEquals(1, logs.count { it.contains("gate closed reason=probe") })
    }

    @Test
    fun `a probe that finished unhealthy skips without waiting`() = runTest {
        probed = false
        probeFinished.value = true

        resync.onForeground()

        assertEquals(0L, currentTime)
        verify(exactly = 0) { archive.startCheckTask() }
        coVerify(exactly = 0) { push.resyncBind() }
    }

    @Test
    fun `an archive loop already running is not reported`() {
        every { archive.startCheckTask() } returns false
        coEvery { push.resyncBind() } returns ResyncResult.SKIP

        val logs = captureLogs { runTest { resync.onForeground() } }

        assertFalse(logs.any { it.contains("[WarmResync] task=") })
    }

    private fun reopen() {
        user = sessionUser()
        token = jwtFor(ACCOUNT)
        inaccessible = false
        probed = true
    }

    /**
     * Lines `L` delivered while [block] ran. `L` delivers asynchronously in order, so lines still
     * queued from an earlier test are dropped by a leading start marker, and a trailing sentinel
     * proves delivery is complete.
     */
    private fun captureLogs(block: () -> Unit): List<String> {
        val lines = CopyOnWriteArrayList<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += message
            }
        }
        L.plant(tree)
        try {
            L.i { START_MARKER }
            block()
            L.i { SENTINEL }
            val deadline = System.currentTimeMillis() + LOG_TIMEOUT_MS
            while (lines.none { it.contains(SENTINEL) }) {
                if (System.currentTimeMillis() > deadline) fail("log sentinel not delivered; captured=$lines")
                Thread.sleep(LOG_POLL_MS)
            }
        } finally {
            L.uproot(tree)
        }
        return lines.drop(lines.indexOfFirst { it.contains(START_MARKER) } + 1)
    }

    private fun sessionUser(): UserData = buildUserData(account = ACCOUNT).apply {
        aciIdentityPublicKey = "pub"
        aciIdentityPrivateKey = "priv"
    }

    private fun jwtFor(uid: String): String {
        val header = b64("""{"alg":"none","typ":"JWT"}""")
        val payload = b64("""{"exp":${System.currentTimeMillis() / 1000 + 600},"uid":"$uid"}""")
        return "$header.$payload.sig"
    }

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private companion object {
        const val ACCOUNT = "+11112223333"
        const val START_MARKER = "[WarmProcessResyncTest] start"
        const val SENTINEL = "[WarmProcessResyncTest] sentinel"
        const val LOG_TIMEOUT_MS = 10_000L
        const val LOG_POLL_MS = 10L
    }
}
