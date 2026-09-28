package com.difft.android.chat.ui.popup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.difft.android.base.BaseActivity
import com.difft.android.base.storage.AppStateDataStoreEntryPoint
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.KeyboardHeightCache
import com.difft.android.base.widget.InsetAwareConstraintLayout
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The popup path must seed the SAME keyboard-height cache the full-screen path writes
 * (`InsetAwareConstraintLayout.applyInsets` → `KeyboardHeightCache.save`). Before this, an install
 * whose keyboard had only ever been shown inside popup chat left `getKeyboardHeight()` at 0, so the
 * chat input fragment could not size its action panel and fell back to wrap-content plus a fixed lift.
 *
 * The write is asynchronous (`appScope.launch`), so the rows block on the recording DataStore rather
 * than idling the main looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PopupKeyboardPanelControllerKeyboardHeightTest {

    private companion object {
        const val NAV_BAR = 60
        const val KB_INSET = 900

        /** What the full-screen path stores for the same insets: IME inset minus navigation bar. */
        const val EXPECTED_HEIGHT = KB_INSET - NAV_BAR
    }

    /**
     * Host context whose orientation can be flipped mid-test, the way a popup Activity that handles
     * `configChanges` sees its own resources change in place under a still-attached view.
     */
    private class RotatableContext(private val activity: Activity) : ContextWrapper(activity) {
        private var current: Context = activity

        fun rotateTo(orientation: Int) {
            val config = Configuration(activity.resources.configuration).apply { this.orientation = orientation }
            current = activity.createConfigurationContext(config)
        }

        override fun getResources(): Resources = current.resources
    }

    private val hostActivity = mockk<BaseActivity>(relaxed = true)
    private val dataStore = RecordingPreferencesDataStore()

    private lateinit var hostContext: RotatableContext
    private lateinit var root: View
    private lateinit var controller: PopupKeyboardPanelController

    @Before
    fun setUp() {
        runBlocking { KeyboardHeightCache.resetForTest() }
        val entryPoint = mockk<AppStateDataStoreEntryPoint>()
        every { entryPoint.appStateDataStore() } returns dataStore
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(any<Context>(), AppStateDataStoreEntryPoint::class.java)
        } returns entryPoint

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        hostContext = RotatableContext(activity)
        root = FrameLayout(hostContext)
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()

        controller = PopupKeyboardPanelController(hostActivity, root)
    }

    @After
    fun tearDown() {
        // Reset (and join the warm-up) while the entry-point mock is still in place, so no straggling
        // coroutine can reach the next test's store.
        runBlocking { KeyboardHeightCache.resetForTest() }
        unmockkStatic(EntryPointAccessors::class)
        clearMocks(hostActivity)
    }

    /** The popup write must land in the shared in-memory cache too, not only on disk. */
    @Test
    fun `a popup keyboard pass is readable from the shared cache without waiting for the write`() {
        insets(imeVisible = true, imeHeight = KB_INSET)

        assertEquals(EXPECTED_HEIGHT, KeyboardHeightCache.get(root.context))
        assertEquals(EXPECTED_HEIGHT, InsetAwareConstraintLayout.getKeyboardHeight(root.context))
        dataStore.awaitWrite()
    }

    private fun insets(imeVisible: Boolean, imeHeight: Int, navBar: Int = NAV_BAR) =
        controller.onWindowInsets(
            navigationBarPx = navBar,
            imeHeightPx = imeHeight,
            imeVisible = imeVisible,
            maxHeightPx = 0,
        )

    /** The fix: a popup keyboard pass seeds the portrait key with the full-screen value. */
    @Test
    fun `a visible IME in the popup persists the keyboard height under the portrait key`() {
        insets(imeVisible = true, imeHeight = KB_INSET)

        val written = dataStore.awaitWrite()
        assertEquals(
            EXPECTED_HEIGHT,
            written[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT]
        )
        assertNull(
            "a portrait pass must not touch the landscape key",
            written[AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE]
        )
    }

    /** Insets repeat every pass; only a changed height may reach the DataStore. */
    @Test
    fun `an unchanged keyboard height is written once, not once per insets pass`() {
        insets(imeVisible = true, imeHeight = KB_INSET)
        dataStore.awaitWrite()

        insets(imeVisible = true, imeHeight = KB_INSET)

        assertNull("an identical insets pass must not re-write", dataStore.awaitNoWrite())
    }

    /**
     * Interpolated hide frames arrive as shrinking heights with the IME still flagged visible; they
     * must not replace the settled height. The next show starts a new stretch and may store a
     * genuinely shorter keyboard.
     */
    @Test
    fun `shrinking frames within a visible stretch are not persisted, the next show is`() {
        insets(imeVisible = true, imeHeight = KB_INSET)
        assertEquals(EXPECTED_HEIGHT, dataStore.awaitWrite()[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT])

        insets(imeVisible = true, imeHeight = 600)
        insets(imeVisible = true, imeHeight = 300)
        assertNull("hide-animation frames must not be persisted", dataStore.awaitNoWrite())
        assertEquals(EXPECTED_HEIGHT, KeyboardHeightCache.get(root.context))

        insets(imeVisible = false, imeHeight = 0)
        insets(imeVisible = true, imeHeight = 700)
        assertEquals(700 - NAV_BAR, dataStore.awaitWrite()[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT])
        assertEquals(700 - NAV_BAR, KeyboardHeightCache.get(root.context))
    }

    /**
     * The popup Activity survives rotation with the IME still visible; the landscape keyboard is
     * shorter, and must land in the landscape slot rather than be dropped as a hide frame.
     */
    @Test
    fun `rotation with the IME kept visible starts a new stretch and stores the landscape height`() {
        insets(imeVisible = true, imeHeight = KB_INSET)
        assertEquals(EXPECTED_HEIGHT, dataStore.awaitWrite()[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT])

        hostContext.rotateTo(Configuration.ORIENTATION_LANDSCAPE)
        insets(imeVisible = true, imeHeight = 500)

        val written = dataStore.awaitWrite()
        assertEquals(500 - NAV_BAR, written[AppStateKeys.KEY_KEYBOARD_HEIGHT_LANDSCAPE])
        assertEquals("portrait keeps its own value", EXPECTED_HEIGHT, written[AppStateKeys.KEY_KEYBOARD_HEIGHT_PORTRAIT])
        assertEquals(500 - NAV_BAR, KeyboardHeightCache.get(root.context))
    }

    /**
     * An IME reported visible with no height (floating/split keyboards, transient frames) has no
     * measurable keyboard, and neither does one whose inset is entirely navigation bar. Writing
     * either would poison the cache the panel is sized from.
     */
    @Test
    fun `an IME with no usable height is not persisted`() {
        insets(imeVisible = true, imeHeight = 0)
        insets(imeVisible = true, imeHeight = NAV_BAR)
        insets(imeVisible = false, imeHeight = KB_INSET)

        assertNull("no measurable keyboard height may be persisted", dataStore.awaitNoWrite())
    }
}
