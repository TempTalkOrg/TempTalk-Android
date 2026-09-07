package com.difft.android.base.storage

import android.content.Context
import android.content.res.Configuration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.appScope
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * [KeyboardHeightCache.get] runs on the main thread inside click handlers, so its contract is:
 * memory only, never a disk wait (Crashlytics ANR 4ef85018). Disk arrives via [KeyboardHeightCache.seed]
 * or a one-shot [KeyboardHeightCache.warm].
 *
 * `appScope` is replaced by an unconfined test scope so launched work runs inline and every assertion
 * is deterministic; a store that never answers would hang a blocking read, which the timeout rule
 * turns into a failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KeyboardHeightCacheTest {

    private companion object {
        const val PERSISTED = 600
        const val MEASURED = 900
    }

    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private lateinit var context: Context
    private lateinit var entryPoint: AppStateDataStoreEntryPoint

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns testScope
        runBlocking { KeyboardHeightCache.resetForTest() }
        context = ApplicationProvider.getApplicationContext()
        entryPoint = mockk()
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(any<Context>(), AppStateDataStoreEntryPoint::class.java)
        } returns entryPoint
    }

    @After
    fun tearDown() {
        runBlocking { KeyboardHeightCache.resetForTest() }
        unmockkStatic(EntryPointAccessors::class)
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
    }

    private fun backedBy(store: RecordingPreferencesDataStore) {
        every { entryPoint.appStateDataStore() } returns store
    }

    private fun persisted(portrait: Int? = null, landscape: Int? = null): Preferences =
        mutablePreferencesOf().apply {
            portrait?.let { this[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT] = it }
            landscape?.let { this[AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE] = it }
        }

    private fun landscapeContext(): Context {
        val config = Configuration(context.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        return context.createConfigurationContext(config)
    }

    /** The ANR shape: the store never answers. get() returns at once and the value arrives later. */
    @Test
    fun `get before the disk answers returns 0 without blocking, then picks up the loaded value`() {
        val store = RecordingPreferencesDataStore(initial = null)
        backedBy(store)

        assertEquals(0, KeyboardHeightCache.get(context))
        assertEquals("get() must have started the one-shot load", 1, store.reads.get())

        store.emit(persisted(portrait = PERSISTED))
        assertEquals(PERSISTED, KeyboardHeightCache.get(context))
    }

    @Test
    fun `warm-up loads the persisted height and later gets are served from memory`() {
        val store = RecordingPreferencesDataStore(persisted(portrait = PERSISTED))
        backedBy(store)

        KeyboardHeightCache.warm(context)

        assertEquals(PERSISTED, KeyboardHeightCache.get(context))
        assertEquals(PERSISTED, KeyboardHeightCache.get(context))
        assertEquals("a warm cache must not touch the store", 1, store.reads.get())
    }

    /** The production path: StoragePreloader hands over the snapshot it already read. */
    @Test
    fun `seed fills the cache without a store read and makes warm a no-op`() {
        val store = RecordingPreferencesDataStore(persisted(portrait = PERSISTED, landscape = PERSISTED + 1))
        backedBy(store)

        KeyboardHeightCache.seed(persisted(portrait = PERSISTED, landscape = PERSISTED + 1))
        KeyboardHeightCache.warm(context)

        assertEquals(PERSISTED, KeyboardHeightCache.get(context))
        assertEquals(PERSISTED + 1, KeyboardHeightCache.get(landscapeContext()))
        assertEquals("seed must not start a disk load", 0, store.reads.get())
    }

    /** A click before the startup seed lands: warm-up already pending, seed still fills, one read total. */
    @Test
    fun `seed after a pending warm-up fills the cache and keeps a single store read`() {
        val store = RecordingPreferencesDataStore(initial = null)
        backedBy(store)

        assertEquals(0, KeyboardHeightCache.get(context))
        KeyboardHeightCache.seed(persisted(portrait = PERSISTED))

        assertEquals(PERSISTED, KeyboardHeightCache.get(context))
        assertEquals(1, store.reads.get())
    }

    @Test
    fun `warm-up runs once per process`() {
        val store = RecordingPreferencesDataStore(persisted(portrait = PERSISTED))
        backedBy(store)

        KeyboardHeightCache.warm(context)
        KeyboardHeightCache.warm(context)
        KeyboardHeightCache.get(landscapeContext())

        assertEquals(1, store.reads.get())
    }

    @Test
    fun `save is visible synchronously, persists once, and skips an unchanged re-save`() {
        val store = RecordingPreferencesDataStore()
        backedBy(store)

        KeyboardHeightCache.save(context, MEASURED)

        assertEquals(MEASURED, KeyboardHeightCache.get(context))
        assertEquals(MEASURED, store.awaitWrite()[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT])

        KeyboardHeightCache.save(context, MEASURED)
        assertEquals("an identical save must not re-write", 1, store.writeCount.get())
    }

    /** A swallowed write failure must not be frozen in by the dedupe: the next identical save retries. */
    @Test
    fun `a failed persist is retried by the next save of the same height`() {
        val store = RecordingPreferencesDataStore()
        every { entryPoint.appStateDataStore() } throws IOException("disk full") andThen store

        KeyboardHeightCache.save(context, MEASURED)
        assertEquals(0, store.writeCount.get())

        KeyboardHeightCache.save(context, MEASURED)
        assertEquals(MEASURED, store.awaitWrite()[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT])

        KeyboardHeightCache.save(context, MEASURED)
        assertEquals("once healed, the dedupe applies again", 1, store.writeCount.get())
    }

    /** The retry marker is per orientation: a failed landscape write is not absorbed by a portrait save. */
    @Test
    fun `a failed landscape persist is retried by landscape, not consumed by an unchanged portrait save`() {
        val store = RecordingPreferencesDataStore()
        val landscape = landscapeContext()
        backedBy(store)
        KeyboardHeightCache.save(context, MEASURED)
        store.awaitWrite()
        every { entryPoint.appStateDataStore() } throws IOException("disk full") andThen store

        KeyboardHeightCache.save(landscape, PERSISTED)
        KeyboardHeightCache.save(context, MEASURED)
        assertEquals("portrait is unchanged and healthy: no write", 1, store.writeCount.get())

        KeyboardHeightCache.save(landscape, PERSISTED)
        assertEquals(PERSISTED, store.awaitWrite()[AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE])
    }

    /**
     * A height measured in this process must not be clobbered by a slower disk load. The store stays
     * silent while the warm-up is pending, so the save's write also waits (like a real DataStore) and
     * the disk value the warm-up finally delivers differs from the live one.
     */
    @Test
    fun `a live save wins over a warm-up that completes later`() {
        val store = RecordingPreferencesDataStore(initial = null)
        backedBy(store)
        KeyboardHeightCache.warm(context)

        KeyboardHeightCache.save(context, MEASURED)
        store.emit(persisted(portrait = PERSISTED))

        assertEquals(MEASURED, KeyboardHeightCache.get(context))
        assertEquals(MEASURED, store.awaitWrite()[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT])
    }

    @Test
    fun `portrait and landscape are independent slots`() {
        val store = RecordingPreferencesDataStore(persisted(portrait = PERSISTED, landscape = PERSISTED + 1))
        backedBy(store)
        val landscape = landscapeContext()
        KeyboardHeightCache.warm(context)

        assertEquals(PERSISTED, KeyboardHeightCache.get(context))
        assertEquals(PERSISTED + 1, KeyboardHeightCache.get(landscape))

        KeyboardHeightCache.save(landscape, MEASURED)

        assertEquals(MEASURED, KeyboardHeightCache.get(landscape))
        assertEquals("a landscape save must not touch portrait", PERSISTED, KeyboardHeightCache.get(context))
        assertEquals(MEASURED, store.awaitWrite()[AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE])
    }

    @Test
    fun `a failed warm-up leaves the cache empty and is not retried`() {
        every { entryPoint.appStateDataStore() } throws IllegalStateException("graph unavailable")

        KeyboardHeightCache.warm(context)
        assertEquals(0, KeyboardHeightCache.get(context))
        KeyboardHeightCache.get(context)

        verify(exactly = 1) { entryPoint.appStateDataStore() }
    }
}
