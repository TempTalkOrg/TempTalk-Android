package com.difft.android.base.ui.glass

import android.app.Activity
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassRecyclerViewTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var source: GlassRecyclerView
    private lateinit var top: RectF
    private lateinit var bottom: RectF

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        source = GlassRecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            itemAnimator = null
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 10
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                    object : RecyclerView.ViewHolder(View(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(300, 150)
                    }) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            }
        }
        activity.setContentView(source)
        val width = View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        source.measure(width, height)
        source.layout(0, 0, 300, 600)
        val location = IntArray(2)
        source.getLocationInWindow(location)
        top = RectF(location[0].toFloat(), location[1].toFloat(), location[0] + 300f, location[1] + 100f)
        bottom = RectF(top.left, top.top + 500f, top.right, top.top + 600f)
        source.glassDamage.acquire()
        source.glassDamage.beginFrame()
        source.glassDamage.endFrame()
    }

    @After
    fun tearDown() { controller.pause().stop().destroy() }

    @Test
    fun `descendant hook forwards target without turning ancestor dirty state into global damage`() {
        val row = source.getChildAt(source.childCount - 1)
        source.onDescendantInvalidated(row, row)
        source.glassDamage.beginFrame()
        assertFalse(source.glassDamage.isDirty(top))
        assertTrue(source.glassDamage.isDirty(bottom))
        source.glassDamage.endFrame()
    }

    @Test
    fun `real RecyclerView scroll changes both source regions`() {
        val previous = source.getChildAdapterPosition(source.getChildAt(0))
        source.scrollBy(0, 150)
        assertTrue(source.getChildAdapterPosition(source.getChildAt(0)) > previous)
        source.glassDamage.beginFrame()
        assertTrue(source.glassDamage.isDirty(top))
        assertTrue(source.glassDamage.isDirty(bottom))
        source.glassDamage.endFrame()
    }

    @Test
    fun `source own invalidation covers decorations and overscroll unlike row invalidation`() {
        source.invalidate()
        source.glassDamage.beginFrame()
        assertTrue(source.glassDamage.isDirty(top))
        assertTrue(source.glassDamage.isDirty(bottom))
        source.glassDamage.endFrame()
    }
}
