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









@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ImageEditorHudV2SeekbarTest {

    



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

        
        
        
        mockkStatic(EntryPointAccessors::class)
        every {
            EntryPointAccessors.fromApplication(any<Context>(), GlobalHiltEntryPoint::class.java)
        } returns globalEntry

        
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
        
        val hud = ImageEditorHudV2(themedContext)
        hud.setMode(ImageEditorHudV2.Mode.DRAW)

        val widthSeekBar = hud.findViewById<androidx.appcompat.widget.AppCompatSeekBar>(
            com.difft.android.chat.R.id.image_editor_hud_draw_width_bar
        )
        widthSeekBar.progress = 50
        
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
