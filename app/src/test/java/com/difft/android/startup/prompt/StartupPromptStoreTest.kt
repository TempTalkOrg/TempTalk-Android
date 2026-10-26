package com.difft.android.startup.prompt

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.storage.AppStateKeys
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The prompt store on a real file-backed app_state DataStore: round trip, pinned JSON names, corruption, atomic edits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StartupPromptStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: StartupPromptStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("app_state.preferences_pb") })
        store = StartupPromptStore(dataStore, Gson())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `absent state is empty`() = runBlocking {
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `records survive a JSON round trip`() = runBlocking {
        val record = PromptRecord(
            shownCount = 3, declines = 2, snoozeUntil = 11, lastShownAt = 12, showingSince = 13,
            pendingActionAt = 14, systemDenials = 2, offerKey = "h",
        )
        store.update { mapOf(PromptId.OPTIONAL_UPDATE to record, PromptId.FULL_SCREEN to PromptRecord()) }
        val reread = StartupPromptStore(dataStore, Gson()).read()
        assertEquals(mapOf(PromptId.OPTIONAL_UPDATE to record, PromptId.FULL_SCREEN to PromptRecord()), reread)
    }

    @Test
    fun `JSON names are the pinned field names`() = runBlocking {
        store.update { mapOf(PromptId.FULL_SCREEN to PromptRecord(declines = 2, offerKey = "k")) }
        val json = dataStore.data.first()[AppStateKeys.STARTUP_PROMPT_STATE]!!
        assertTrue(json, json.contains("\"FULL_SCREEN\":{") && json.contains("\"declines\":2") && json.contains("\"offerKey\":\"k\""))
    }

    @Test
    fun `missing fields default and unknown ids are dropped`() = runBlocking {
        dataStore.edit { it[AppStateKeys.STARTUP_PROMPT_STATE] = """{"FULL_SCREEN":{"declines":2},"REMOVED_ID":{"declines":9}}""" }
        assertEquals(mapOf(PromptId.FULL_SCREEN to PromptRecord(declines = 2)), store.read())
    }

    @Test
    fun `corrupt value reads as empty and the next write replaces it`() = runBlocking {
        dataStore.edit { it[AppStateKeys.STARTUP_PROMPT_STATE] = "{not json" }
        assertTrue(store.read().isEmpty())
        store.update { it + (PromptId.FULL_SCREEN to PromptRecord(declines = 1)) }
        assertEquals(1, store.read()[PromptId.FULL_SCREEN]?.declines)
    }

    @Test
    fun `concurrent mutations are not lost`() = runBlocking {
        (1..20).map { i ->
            scope.async {
                val id = PromptId.entries[i % PromptId.entries.size]
                store.update { it.edit(id) { r -> r.copy(shownCount = r.shownCount + 1) } }
            }
        }.awaitAll()
        assertEquals(20, store.read().values.sumOf { it.shownCount })
        assertTrue(dataStore.data.first()[AppStateKeys.STARTUP_PROMPT_STATE]!!.isNotBlank())
    }
}
