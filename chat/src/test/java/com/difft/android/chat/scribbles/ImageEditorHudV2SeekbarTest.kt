package com.difft.android.chat.scribbles

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.GlobalHiltEntryPoint
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Integration test: real XML inflate of `v2_media_image_editor_hud.xml` → manipulate seekbar →
 * assert writes reach UserData (design §3.9 table row 2, plus issue #725 §10.2 hot-write throttling).
 *
 * Approach:
 *   - Uses Robolectric so `LayoutInflater` has a live Android runtime.
 *   - Stubs Hilt's `EntryPointAccessors.fromApplication` so the lazy `userManager`
 *     property in ImageEditorHudV2 (resolved via `context.globalHiltServices()`,
 *     which internally calls `EntryPointAccessors.fromApplication`) returns an
 *     in-memory UserManager without bringing up a full Hilt graph.
 *
 * Uses a local in-memory UserManager implementation rather than `FakeUserManager`
 * from `:base` testFixtures because the testFixtures Kotlin source set is not
 * compiled for downstream consumers (see base/build.gradle.kts workaround comment).
 *
 * **Hot-write throttling (issue #725 §10.2)**: writes are deferred to
 * `onStopTrackingTouch` — `onProgressChanged` now updates only the in-view preview.
 * Tests fire the seekbar's `OnSeekBarChangeListener.onStopTrackingTouch` to assert
 * persistence; setting `widthSeekBar.progress` alone is not expected to persist.
 *
 * Covers all affected paths:
 *   - Write path (3 modes): setupWidthSeekBar onStopTrackingTouch → UserData via userManager.update
 *   - Read path (3 modes): presentMode{Draw|Highlight|Blur} → widthSeekBar.progress from UserData
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ImageEditorHudV2SeekbarTest {

    /**
     * In-memory UserManager for this test. Mirrors `FakeUserManager` in :base testFixtures
     * but lives in the chat module to avoid the testFixtures Kotlin compilation gap.
     */
    private class InMemoryUserManager(
        private var data: UserData? = UserData()
    ) : UserManager {
        override fun setUserData(userData: UserData, commit: Boolean) {
            this.data = userData
        }

        override fun getUserData(): UserData? = data
    }

    private lateinit var userManager: InMemoryUserManager
    private lateinit var themedContext: Context

    @Before
    fun setUp() {
        userManager = InMemoryUserManager()

        val globalEntry = mockk<GlobalHiltEntryPoint>(relaxed = true)
        every { globalEntry.userManager } returns userManager

        // ImageEditorHudV2#userManager calls context.globalHiltServices(), which internally
        // calls EntryPointAccessors.fromApplication(context). Stub that static so the View's
        // lazy property resolves to our InMemoryUserManager.
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(any<Context>(), GlobalHiltEntryPoint::class.java)
        } returns globalEntry

        // Wrap ApplicationContext with an AppCompat theme so AppCompatSeekBar can inflate.
        val appCtx = RuntimeEnvironment.getApplication()
        themedContext = ContextThemeWrapper(
            appCtx,
            androidx.appcompat.R.style.Theme_AppCompat_Light
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(EntryPointAccessors::class)
    }

    /**
     * Retrieves the [android.widget.SeekBar.OnSeekBarChangeListener] installed by
     * `ImageEditorHudV2.setupWidthSeekBar` via reflection. The Android SeekBar
     * does not expose a getter for this listener, so we walk the class hierarchy
     * to find the field whose type matches `OnSeekBarChangeListener`.
     *
     * Robolectric/Android internally store this on different ancestor classes
     * across SDK versions, so matching by type (rather than a hardcoded field
     * name) is more portable. Issue #725 §10.2 throttling means tests must invoke
     * `onStopTrackingTouch` to verify persistence.
     */
    private fun listenerOf(
        seekBar: android.widget.SeekBar,
    ): android.widget.SeekBar.OnSeekBarChangeListener? {
        var clazz: Class<*>? = seekBar::class.java
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (field.type == android.widget.SeekBar.OnSeekBarChangeListener::class.java) {
                    field.isAccessible = true
                    return field.get(seekBar) as? android.widget.SeekBar.OnSeekBarChangeListener
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    @Test
    fun `seekbar onStopTrackingTouch in DRAW mode writes imageEditorMarkerPercentage to UserData`() {
        val hud = ImageEditorHudV2(themedContext)
        hud.setMode(ImageEditorHudV2.Mode.DRAW)

        val widthSeekBar = hud.findViewById<androidx.appcompat.widget.AppCompatSeekBar>(
            com.difft.android.chat.R.id.image_editor_hud_draw_width_bar
        )
        widthSeekBar.progress = 42
        // Hot-write throttling: setting progress alone should NOT persist; persistence
        // happens once on `onStopTrackingTouch`.
        listenerOf(widthSeekBar)?.onStopTrackingTouch(widthSeekBar)

        assertEquals(42, userManager.getUserData()!!.imageEditorMarkerPercentage)
        assertEquals(0, userManager.getUserData()!!.imageEditorHighlighterPercentage)
        assertEquals(0, userManager.getUserData()!!.imageEditorBlurPercentage)
    }

    @Test
    fun `seekbar onStopTrackingTouch in HIGHLIGHT mode writes imageEditorHighlighterPercentage to UserData`() {
        val hud = ImageEditorHudV2(themedContext)
        hud.setMode(ImageEditorHudV2.Mode.HIGHLIGHT)

        val widthSeekBar = hud.findViewById<androidx.appcompat.widget.AppCompatSeekBar>(
            com.difft.android.chat.R.id.image_editor_hud_draw_width_bar
        )
        widthSeekBar.progress = 77
        listenerOf(widthSeekBar)?.onStopTrackingTouch(widthSeekBar)

        assertEquals(77, userManager.getUserData()!!.imageEditorHighlighterPercentage)
        assertEquals(0, userManager.getUserData()!!.imageEditorMarkerPercentage)
        assertEquals(0, userManager.getUserData()!!.imageEditorBlurPercentage)
    }

    @Test
    fun `seekbar onStopTrackingTouch in BLUR mode writes imageEditorBlurPercentage to UserData`() {
        val hud = ImageEditorHudV2(themedContext)
        hud.setMode(ImageEditorHudV2.Mode.BLUR)

        val widthSeekBar = hud.findViewById<androidx.appcompat.widget.AppCompatSeekBar>(
            com.difft.android.chat.R.id.image_editor_hud_draw_width_bar
        )
        widthSeekBar.progress = 33
        listenerOf(widthSeekBar)?.onStopTrackingTouch(widthSeekBar)

        assertEquals(33, userManager.getUserData()!!.imageEditorBlurPercentage)
        assertEquals(0, userManager.getUserData()!!.imageEditorMarkerPercentage)
        assertEquals(0, userManager.getUserData()!!.imageEditorHighlighterPercentage)
    }

    @Test
    fun `seekbar progress alone does NOT persist - hot-write throttling`() {
        // Issue #725 §10.2: `onProgressChanged` fires up to 100 times per drag.
        // The legacy implementation wrote on every tick. After throttling, only
        // `onStopTrackingTouch` persists — verify no write fires during drag.
        val hud = ImageEditorHudV2(themedContext)
        hud.setMode(ImageEditorHudV2.Mode.DRAW)

        val widthSeekBar = hud.findViewById<androidx.appcompat.widget.AppCompatSeekBar>(
            com.difft.android.chat.R.id.image_editor_hud_draw_width_bar
        )
        widthSeekBar.progress = 50
        // We deliberately do NOT fire onStopTrackingTouch. UserData stays at default.
        assertEquals(0, userManager.getUserData()!!.imageEditorMarkerPercentage)
    }

    @Test
    fun `entering DRAW mode sets seekbar progress from stored imageEditorMarkerPercentage`() {
        userManager.setUserData(
            UserData(imageEditorMarkerPercentage = 55),
            commit = false
        )
        val hud = ImageEditorHudV2(themedContext)
        hud.setMode(ImageEditorHudV2.Mode.DRAW)

        val widthSeekBar = hud.findViewById<androidx.appcompat.widget.AppCompatSeekBar>(
            com.difft.android.chat.R.id.image_editor_hud_draw_width_bar
        )
        assertEquals(55, widthSeekBar.progress)
    }

    @Test
    fun `entering HIGHLIGHT mode sets seekbar progress from stored imageEditorHighlighterPercentage`() {
        userManager.setUserData(
            UserData(imageEditorHighlighterPercentage = 66),
            commit = false
        )
        val hud = ImageEditorHudV2(themedContext)
        hud.setMode(ImageEditorHudV2.Mode.HIGHLIGHT)

        val widthSeekBar = hud.findViewById<androidx.appcompat.widget.AppCompatSeekBar>(
            com.difft.android.chat.R.id.image_editor_hud_draw_width_bar
        )
        assertEquals(66, widthSeekBar.progress)
    }

    @Test
    fun `entering BLUR mode sets seekbar progress from stored imageEditorBlurPercentage`() {
        userManager.setUserData(
            UserData(imageEditorBlurPercentage = 88),
            commit = false
        )
        val hud = ImageEditorHudV2(themedContext)
        hud.setMode(ImageEditorHudV2.Mode.BLUR)

        val widthSeekBar = hud.findViewById<androidx.appcompat.widget.AppCompatSeekBar>(
            com.difft.android.chat.R.id.image_editor_hud_draw_width_bar
        )
        assertEquals(88, widthSeekBar.progress)
    }

    @Test
    fun `default UserData yields seekbar progress zero on first open`() {
        val hud = ImageEditorHudV2(themedContext)
        hud.setMode(ImageEditorHudV2.Mode.DRAW)

        val widthSeekBar = hud.findViewById<androidx.appcompat.widget.AppCompatSeekBar>(
            com.difft.android.chat.R.id.image_editor_hud_draw_width_bar
        )
        assertEquals(0, widthSeekBar.progress)
    }
}
