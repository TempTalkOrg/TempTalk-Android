package com.difft.android.me

import android.app.Activity
import android.util.TypedValue
import com.difft.android.databinding.MeFragmentBinding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeTextScalingTest {

    private lateinit var controller: ActivityController<Activity>
    private lateinit var binding: MeFragmentBinding

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        binding = MeFragmentBinding.inflate(activity.layoutInflater)
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, controller.get().resources.displayMetrics)

    @Test
    fun `default size scales the rows to 16sp`() {
        MeTextScaling.apply(binding, isLarger = false)

        assertEquals(sp(16f), binding.tvThemeTitle.textSize, 0.5f)
        assertEquals(sp(14f), binding.tvProfile.textSize, 0.5f)
    }

    @Test
    fun `the appearance settings are one row without a value, and their old rows are gone`() {
        val ids = controller.get().resources
        val root = binding.root
        assertEquals(controller.get().getString(com.difft.android.chat.R.string.me_appearance), binding.tvThemeTitle.text.toString())
        listOf("tv_theme", "cl_text_size", "cl_display_size", "cl_status_bar", "cl_navigation_placement").forEach { name ->
            val id = ids.getIdentifier(name, "id", controller.get().packageName)
            assertEquals("$name is still in the Me layout", null, if (id == 0) null else root.findViewById<android.view.View>(id))
        }
        assertEquals(android.view.View.VISIBLE, binding.clLanguage.visibility)
        assertEquals(android.view.View.VISIBLE, binding.tvLanguage.visibility)
    }

    @Test
    fun `large size scales the rows to 24sp`() {
        MeTextScaling.apply(binding, isLarger = true)

        assertEquals(sp(24f), binding.tvThemeTitle.textSize, 0.5f)
        assertEquals(sp(21f), binding.tvProfile.textSize, 0.5f)
    }
}
