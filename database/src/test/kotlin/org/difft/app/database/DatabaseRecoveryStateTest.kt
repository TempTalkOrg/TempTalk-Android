package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the recovery circuit-breaker ([DatabaseRecoveryState]) re-introduced to
 * bound consecutive recovery attempts (design `tmp/db-recovery-redesign/design.md` §6 /
 * ARCH-CRIT-1 terminal state). Pure SharedPreferences logic — no native WCDB, fully
 * runnable on the JVM host via Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseRecoveryStateTest {

    private lateinit var ctx: Context
    private lateinit var state: DatabaseRecoveryState

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // Fresh prefs per test: clear the dedicated file.
        ctx.getSharedPreferences("db_recovery_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
        state = DatabaseRecoveryState(ctx)
    }

    @Test
    fun `incrementAndGet counts up from one`() {
        assertEquals(1, state.incrementAndGet())
        assertEquals(2, state.incrementAndGet())
        assertEquals(3, state.incrementAndGet())
    }

    @Test
    fun `reset clears the count back to zero`() {
        state.incrementAndGet()
        state.incrementAndGet()
        state.reset()
        assertEquals(1, state.incrementAndGet(), "After reset, the next increment starts at 1 again")
    }

    @Test
    fun `count persists across instances backed by the same prefs file`() {
        state.incrementAndGet()
        state.incrementAndGet()
        // Simulate a process restart: a brand-new instance over the same file.
        val reloaded = DatabaseRecoveryState(ctx)
        assertEquals(3, reloaded.incrementAndGet(), "The count must survive a restart (persisted)")
    }

    @Test
    fun `breaker trips only after MAX_RECOVERY_ATTEMPTS exceeded`() {
        // Attempts 1..MAX are allowed; the first value that EXCEEDS MAX is the terminal trip.
        repeat(DatabaseRecoveryState.MAX_RECOVERY_ATTEMPTS) {
            val n = state.incrementAndGet()
            assertTrue(
                n <= DatabaseRecoveryState.MAX_RECOVERY_ATTEMPTS,
                "Attempt $n must still be within the allowed window",
            )
        }
        val tripping = state.incrementAndGet()
        assertTrue(
            tripping > DatabaseRecoveryState.MAX_RECOVERY_ATTEMPTS,
            "The (MAX+1)th attempt must exceed the limit and trip the breaker",
        )
    }
}
