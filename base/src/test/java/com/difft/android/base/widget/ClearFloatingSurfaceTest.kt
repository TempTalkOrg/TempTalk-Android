package com.difft.android.base.widget

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.difft.android.base.R
import com.difft.android.base.ui.glass.GlassMode
import com.difft.android.base.ui.glass.GlassSurfaceBinding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClearFloatingSurfaceTest {
    private var previousMode: Boolean? = null

    @Before fun enableGlass() {
        previousMode = ReflectionHelpers.getStaticField(GlassMode::class.java, "live")
        ReflectionHelpers.setStaticField(GlassMode::class.java, "live", true)
    }

    @After fun restoreMode() = ReflectionHelpers.setStaticField(GlassMode::class.java, "live", previousMode)

    @Test
    fun `clearing a glass skin closes its sampling binding and removes the skin`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = View(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(view) })
        view.applyFloatingSurface(cornerRadiusPx = 22f, glass = true, pressable = true)
        val binding = view.getTag(R.id.glass_surface_binding) as? GlassSurfaceBinding
        assertNotNull(binding)
        assertEquals(1, view.attachListenerCount())

        view.clearFloatingSurface()

        assertNull(view.getTag(R.id.glass_surface_binding))
        assertEquals("the binding detached from the view", 0, view.attachListenerCount())
        assertNull(view.background)
        assertNull(view.foreground)
        assertEquals(0f, view.elevation, 0f)
    }

    private fun View.attachListenerCount(): Int {
        val info = ReflectionHelpers.getField<Any?>(this, "mListenerInfo") ?: return 0
        return ReflectionHelpers.getField<Collection<*>?>(info, "mOnAttachStateChangeListeners")?.size ?: 0
    }
}
