package com.difft.android.migration.v2.transfer

import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.android.client.PrepareUploadRejectedException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The account-switch wait (登录时导入由手机决定 §5.4): transport failures retry every 5 s for 5 minutes and
 * announce the wait once; a 401 in this branch is the Desktop's cancel, not a stale code; any other
 * typed rejection is passed through untouched.
 */
class PeerConfirmRetryTest {

    private var now = 0L
    private val delays = mutableListOf<Long>()
    private val delayFn: suspend (Long) -> Unit = { ms -> delays += ms; now += ms }

    @Test
    fun `transport failures retry until the peer answers and announce the wait once`() = runTest {
        var announcements = 0
        var attempts = 0
        val result = PeerConfirmRetry.run(onWaiting = { announcements++ }, clock = { now }, delayFn = delayFn) {
            attempts++
            when (attempts) {
                1 -> throw SocketTimeoutException("read timed out")
                2 -> throw ConnectException("refused")
                else -> "session"
            }
        }
        assertEquals("session", result)
        assertEquals(3, attempts)
        assertEquals(1, announcements)
        assertEquals(listOf(PeerConfirmRetry.INTERVAL_MS, PeerConfirmRetry.INTERVAL_MS), delays)
    }

    @Test
    fun `a stale-pin rejection in this branch is the peer cancelling`() = runTest {
        val e = assertThrows(PrepareUploadRejectedException::class.java) {
            kotlinx.coroutines.runBlocking {
                PeerConfirmRetry.run<Unit>(onWaiting = {}, clock = { now }, delayFn = delayFn) {
                    throw PrepareUploadRejectedException(PrepareUploadReasons.PIN_REQUIRED)
                }
            }
        }
        assertEquals(PrepareUploadReasons.PEER_CANCELLED, e.reason)
    }

    @Test
    fun `other typed rejections pass through unchanged`() = runTest {
        val e = assertThrows(PrepareUploadRejectedException::class.java) {
            kotlinx.coroutines.runBlocking {
                PeerConfirmRetry.run<Unit>(onWaiting = {}, clock = { now }, delayFn = delayFn) {
                    throw PrepareUploadRejectedException("environment-mismatch")
                }
            }
        }
        assertEquals("environment-mismatch", e.reason)
    }

    @Test
    fun `the budget ends the wait with its own token`() = runTest {
        var attempts = 0
        val e = assertThrows(PrepareUploadRejectedException::class.java) {
            kotlinx.coroutines.runBlocking {
                PeerConfirmRetry.run<Unit>(onWaiting = {}, clock = { now }, delayFn = delayFn) {
                    attempts++
                    throw SocketTimeoutException("read timed out")
                }
            }
        }
        assertEquals(PrepareUploadReasons.PEER_CONFIRM_TIMEOUT, e.reason)
        assertEquals((PeerConfirmRetry.BUDGET_MS / PeerConfirmRetry.INTERVAL_MS + 1).toInt(), attempts)
    }
}
