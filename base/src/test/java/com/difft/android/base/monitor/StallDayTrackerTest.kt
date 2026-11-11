package com.difft.android.base.monitor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.storage.AppStateKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class StallDayTrackerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60_000L

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var tracker: StallDayTracker
    private var wall = 100 * day + 5_000L

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("stall.preferences_pb") })
        tracker = StallDayTracker(store).apply {
            wallClock = { wall }
            zoneOffsetMs = { 0L }
            launch = { block -> runBlocking { block() } }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun days() = runBlocking { tracker.stallDayKeys() }

    @Test
    fun `stalls are counted per day`() {
        tracker.onStall()
        tracker.onStall()
        assertEquals(setOf(100L), days())
        wall += day
        tracker.onStall()
        assertEquals(setOf(100L, 101L), days())
    }

    @Test
    fun `days leave the window after seven days`() {
        tracker.onStall()
        wall += 6 * day
        assertEquals(setOf(100L), days())
        wall += day
        assertEquals(emptySet(), days())
    }

    @Test
    fun `days are local calendar days`() {
        wall = 101 * day - 60_000L
        tracker.zoneOffsetMs = { 8 * 60 * 60_000L }
        tracker.onStall()
        assertEquals(setOf(101L), days())
    }

    @Test
    fun `a switch-on before this was kept starts counting from the first read`() {
        assertEquals(100L, runBlocking { tracker.keepAliveOnDay() })
        val next = StallDayTracker(store).apply {
            wallClock = { wall + 3 * day }
            zoneOffsetMs = { 0L }
        }
        assertEquals(100L, runBlocking { next.keepAliveOnDay() }, "the first read is stored for later processes")
    }

    @Test
    fun `the keep-alive switch-on day is kept and replaced`() {
        tracker.onKeepAliveEnabled()
        assertEquals(100L, runBlocking { tracker.keepAliveOnDay() })
        wall += 3 * day
        tracker.onKeepAliveEnabled()
        assertEquals(103L, runBlocking { tracker.keepAliveOnDay() })
        wall -= 2 * day
        assertEquals(101L, runBlocking { tracker.keepAliveOnDay() }, "a clock moved back reads the day as today")
        wall += 2 * day
        assertEquals(101L, runBlocking { tracker.keepAliveOnDay() }, "the corrected day is kept, so later days count")
    }

    @Test
    fun `records are visible before their writes land`() {
        tracker.launch = {} 
        tracker.onKeepAliveEnabled()
        tracker.onStall()

        assertEquals(100L, runBlocking { tracker.keepAliveOnDay() })
        assertEquals(setOf(100L), days())
        wall += 7 * day
        assertEquals(emptySet(), days(), "unwritten days leave the window too")
    }

    @Test
    fun `a write landing after midnight keeps the stall on its own day`() {
        var pending: (suspend () -> Unit)? = null
        tracker.launch = { pending = it }
        wall = 101 * day - 1_000L
        tracker.onStall()
        wall = 101 * day + 1_000L
        runBlocking { pending!!() }

        assertEquals(setOf(100L), days())
    }

    @Test
    fun `writes landing in reverse order keep both days`() {
        val pending = mutableListOf<suspend () -> Unit>()
        tracker.launch = { pending += it }
        tracker.onStall() 
        wall += day
        tracker.onStall() 
        runBlocking { pending[1](); pending[0]() }

        val next = StallDayTracker(store).apply {
            wallClock = { wall }
            zoneOffsetMs = { 0L }
        }
        assertEquals(setOf(100L, 101L), runBlocking { next.stallDayKeys() })
    }

    @Test
    fun `a corrected switch-on day is persisted for the next process`() {
        runBlocking { store.edit { it[AppStateKeys.BG_KEEP_ALIVE_ON_DAY] = 130L } }
        assertEquals(100L, runBlocking { tracker.keepAliveOnDay() })
        val next = StallDayTracker(store).apply {
            wallClock = { wall + 2 * day }
            zoneOffsetMs = { 0L }
        }
        assertEquals(100L, runBlocking { next.keepAliveOnDay() })
    }

    @Test
    fun `a day after today empties the window`() {
        runBlocking { store.edit { it[AppStateKeys.BG_STALL_DAYS] = "d100:1,d105:1" } }
        assertEquals(emptySet(), days())
    }
}
