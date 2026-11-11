package com.difft.android.dualpane

import android.view.ContextThemeWrapper
import android.view.animation.AnimationUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.difft.android.base.R as BaseR
import com.difft.android.chat.R as ChatR





@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PaneMotionWindowAnimationTest {

    private fun windowAnimationStyleOf(theme: Int): Int {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), theme)
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.windowAnimationStyle))
        return attrs.getResourceId(0, 0).also { attrs.recycle() }
    }

    @Test
    fun `the window animations last as long as the pane motion`() {
        val app = RuntimeEnvironment.getApplication()
        listOf(BaseR.anim.page_push_enter, BaseR.anim.page_push_exit, BaseR.anim.page_pop_enter, BaseR.anim.page_pop_exit)
            .forEach { assertEquals(PaneMotion.DURATION_MS, AnimationUtils.loadAnimation(app, it).duration) }
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `Theme Chative slides its Activities, a translucent window does not - day`() {
        assertEquals(BaseR.style.Animation_Chative_Activity, windowAnimationStyleOf(BaseR.style.Theme_Chative))
        assertEquals(android.R.style.Animation_Translucent, windowAnimationStyleOf(ChatR.style.Theme_TT_TransparentActivity))
    }

    @Test
    @Config(qualifiers = "night")
    fun `Theme Chative slides its Activities, a translucent window does not - night`() {
        assertEquals(BaseR.style.Animation_Chative_Activity, windowAnimationStyleOf(BaseR.style.Theme_Chative))
        assertEquals(android.R.style.Animation_Translucent, windowAnimationStyleOf(ChatR.style.Theme_TT_TransparentActivity))
    }
}
