package com.difft.android.chat.ui.popup

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.difft.android.base.BaseActivity
import com.difft.android.base.storage.AppStateDataStoreEntryPoint
import com.difft.android.base.storage.AppStateKeys
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The popup path must seed the SAME keyboard-height cache the full-screen path writes
 * (`InsetAwareConstraintLayout.applyInsets` → `saveKeyboardHeight`). Before this, an install whose
 * keyboard had only ever been shown inside popup chat left `getKeyboardHeight()` at 0, so the chat
 * input fragment could not size its action panel and fell back to wrap-content plus a fixed lift.
 *
 * The write is asynchronous (`appScope.launch(Dispatchers.IO)`), so the rows block on the recording
 * DataStore rather than idling the main looper.
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

    /** Minimal in-memory [DataStore] that records every committed write for assertion. */
    private class RecordingDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val writes = LinkedBlockingQueue<Preferences>()

        override val data: Flow<Preferences> get() = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            writes.put(updated)
            return updated
        }

        fun awaitWrite(): Preferences = requireNotNull(writes.poll(5, TimeUnit.SECONDS)) {
            "expected a keyboard-height write, none arrived"
        }

        fun awaitNoWrite(): Preferences? = writes.poll(500, TimeUnit.MILLISECONDS)
    }

    private val hostActivity = mockk<BaseActivity>(relaxed = true)
    private val dataStore = RecordingDataStore()

    private lateinit var root: View
    private lateinit var controller: PopupKeyboardPanelController

    @Before
    fun setUp() {
        val entryPoint = mockk<AppStateDataStoreEntryPoint>()
        every { entryPoint.appStateDataStore() } returns dataStore
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(any<Context>(), AppStateDataStoreEntryPoint::class.java)
        } returns entryPoint

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = FrameLayout(activity)
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()

        controller = PopupKeyboardPanelController(hostActivity, root)
    }

    @After
    fun tearDown() {
        unmockkStatic(EntryPointAccessors::class)
        clearMocks(hostActivity)
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
