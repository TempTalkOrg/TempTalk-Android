package com.difft.android.chat.setting.archive

import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.log.lumberjack.L
import com.difft.android.test.harness.LogCapture
import dagger.Lazy
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The archive loop of [MessageArchiveManager] (#1395): one loop per process whoever starts it
 * (IndexActivity or the warm-process foreground resync); a cipher-key failure skips a round
 * unstamped and is retried next round, logged once per process; any other exception still
 * escapes; a round while the DB is inaccessible is skipped silently. WCDB's native library does not
 * load on the JVM, so the round's DB work ([MessageArchiveManager.archivePass]) is faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageArchiveLoopTest {

    private var now = 1_000L
    private var inaccessible = false
    private var passes = 0
    private var onPass: () -> Unit = {}

    private val manager = MessageArchiveManager(
        dbRoomStore = mockk(relaxed = true),
        groupRepo = mockk(relaxed = true),
        globalConfigsManager = mockk(relaxed = true),
        chatHttpClient = mockk(relaxed = true),
        dbMessageStore = mockk(relaxed = true),
        localMessageCreator = Lazy { mockk(relaxed = true) },
        conversationSettingsManager = Lazy { mockk(relaxed = true) },
        gson = mockk(relaxed = true),
        serverTimeSyncer = mockk(relaxed = true),
    ).apply {
        isDbInaccessible = { inaccessible }
        elapsedRealtime = { now }
        archivePass = {
            passes++
            onPass()
        }
    }

    @Test
    fun `only the first start returns true, so a second caller cannot stack a loop`() {
        // The launched loop exits at once on an inaccessible DB; only the guard is under test here.
        inaccessible = true

        assertTrue(manager.startCheckTask())
        assertFalse(manager.startCheckTask())
        assertFalse(manager.startCheckTask())
    }

    @Test
    fun `a key failure skips the round unstamped and the next round retries`() = runTest {
        onPass = { throw WCDBKeyUnavailableException("key unavailable") }
        val loop = launch { manager.runArchiveLoop() }
        runCurrent()
        assertEquals(1, passes)
        assertEquals(0L, manager.lastCheckTimeForTest)

        onPass = {}
        advanceRound()

        assertEquals(2, passes)
        assertEquals(now, manager.lastCheckTimeForTest)
        loop.cancel()
    }

    @Test
    fun `repeated key failures log once per process`() {
        val capture = LogCapture()
        capture.recording {
            // L delivers asynchronously; the marker separates lines still queued from earlier tests.
            L.i { START_MARKER }
            runTest {
                onPass = { throw WCDBKeyUnavailableException("key unavailable") }
                val loop = launch { manager.runArchiveLoop() }
                runCurrent()
                repeat(3) { advanceRound() }
                assertEquals(4, passes)
                loop.cancel()
            }
            L.i { SENTINEL }
            awaitLine("sentinel") { it.contains(SENTINEL) }
        }
        val lines = capture.messages.dropWhile { !it.contains(START_MARKER) }
        assertEquals(1, lines.count { it.contains("archive round skipped") })
    }

    @Test
    fun `any other exception escapes the loop`() = runTest {
        onPass = { throw IllegalStateException("boom") }

        val error = runCatching { manager.runArchiveLoop() }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(0L, manager.lastCheckTimeForTest)
    }

    @Test
    fun `an inaccessible DB skips the round silently and the loop keeps its interval`() = runTest {
        inaccessible = true
        val loop = launch { manager.runArchiveLoop() }
        runCurrent()
        advanceTimeBy(INTERVAL_MS / 2)
        runCurrent()
        assertEquals("no busy loop, no pass", 0, passes)
        assertEquals(0L, manager.lastCheckTimeForTest)

        inaccessible = false
        advanceRound()

        assertEquals(1, passes)
        loop.cancel()
    }

    @Test
    fun `cancelling the loop ends it`() = runTest {
        val loop = launch { manager.runArchiveLoop() }
        runCurrent()
        loop.cancel()
        runCurrent()

        assertTrue(loop.isCancelled)
        assertTrue(loop.isCompleted)
    }

    /** The foreground interval elapses and the monotonic clock moves with it. */
    private fun TestScope.advanceRound() {
        now += INTERVAL_MS
        advanceTimeBy(INTERVAL_MS + 1)
        runCurrent()
    }

    private companion object {
        const val INTERVAL_MS = 5 * 60 * 1000L
        const val START_MARKER = "[MessageArchiveLoopTest] start"
        const val SENTINEL = "[MessageArchiveLoopTest] sentinel"
    }
}
