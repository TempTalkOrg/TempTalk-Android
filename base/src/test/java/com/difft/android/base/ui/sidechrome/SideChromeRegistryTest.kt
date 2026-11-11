package com.difft.android.base.ui.sidechrome

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SideChromeRegistryTest {

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
        fun move(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private val back = {}
    private fun model(id: String) = SideChromeModel(actions = listOf(RailAction(id = id, icon = 1, contentDescription = id, onClick = {})))

    @Test
    fun `the bar shows the latest shown page`() {
        val registry = SideChromeRegistry()
        val list = Owner().apply { move(Lifecycle.State.RESUMED) }
        val detail = Owner().apply { move(Lifecycle.State.RESUMED) }
        registry.publish(list, model("list"))
        registry.publish(detail, model("detail"))
        assertEquals("detail", registry.current?.actions?.single()?.id)

        
        detail.move(Lifecycle.State.CREATED)
        assertEquals("list", registry.current?.actions?.single()?.id)
        detail.move(Lifecycle.State.RESUMED)
        assertEquals("detail", registry.current?.actions?.single()?.id)
    }

    @Test
    fun `a started neighbour never outranks the resumed page`() {
        val registry = SideChromeRegistry()
        val current = Owner().apply { move(Lifecycle.State.RESUMED) }
        registry.publish(current, model("current"))
        
        val neighbour = Owner().apply { move(Lifecycle.State.STARTED) }
        registry.publish(neighbour, model("neighbour"))
        assertEquals("current", registry.current?.actions?.single()?.id)

        
        current.move(Lifecycle.State.STARTED)
        neighbour.move(Lifecycle.State.RESUMED)
        assertEquals("neighbour", registry.current?.actions?.single()?.id)
    }

    @Test
    fun `a dialog pausing the window keeps the bar`() {
        val window = Owner().apply { move(Lifecycle.State.RESUMED) }
        val registry = SideChromeRegistry(window.lifecycle)
        val page = Owner().apply { move(Lifecycle.State.RESUMED) }
        registry.publish(page, model("page"))
        window.move(Lifecycle.State.STARTED)
        page.move(Lifecycle.State.STARTED)
        assertEquals("page", registry.current?.actions?.single()?.id)
    }

    @Test
    fun `a dialog pausing an activity that is its own page keeps the bar`() {
        
        
        val activity = Owner().apply { move(Lifecycle.State.RESUMED) }
        val registry = SideChromeRegistry(activity.lifecycle)
        registry.publish(activity, model("page"))
        activity.move(Lifecycle.State.STARTED)
        assertEquals("page", registry.current?.actions?.single()?.id)
    }

    @Test
    fun `a hidden tab's page stops counting while the window is resumed`() {
        
        
        val window = Owner().apply { move(Lifecycle.State.RESUMED) }
        val registry = SideChromeRegistry(window.lifecycle)
        val chat = Owner().apply { move(Lifecycle.State.RESUMED) }
        registry.publish(chat, model("call"))
        assertEquals("call", registry.current?.actions?.single()?.id)

        chat.move(Lifecycle.State.STARTED)
        assertNull(registry.current)

        
        window.move(Lifecycle.State.STARTED)
        assertNull(registry.current)
    }

    @Test
    fun `a page covering the list wins over the list whatever order they resumed in`() {
        val registry = SideChromeRegistry()
        val detail = Owner().apply { move(Lifecycle.State.RESUMED) }
        registry.publish(detail, model("detail"))
        
        val list = Owner().apply { move(Lifecycle.State.RESUMED) }
        registry.publish(list, model("list"), base = true)

        registry.preferBase = false
        assertEquals("detail", registry.current?.actions?.single()?.id)
        registry.preferBase = true
        assertEquals("list", registry.current?.actions?.single()?.id)
    }

    @Test
    fun `a covering page with its own chrome cannot fall back to a base list action`() {
        val registry = SideChromeRegistry()
        val list = Owner().apply { move(Lifecycle.State.RESUMED) }
        registry.publish(list, model("Chats add"), base = true)
        assertEquals("Chats add", registry.current?.actions?.single()?.id)
        registry.preferBase = false
        assertNull("a search layer can opt out of publishing rail controls", registry.current)

        val detail = Owner().apply { move(Lifecycle.State.RESUMED) }
        registry.publish(detail, model("detail"))
        assertEquals("detail", registry.current?.actions?.single()?.id)
        detail.move(Lifecycle.State.DESTROYED)
        assertNull(registry.current)

        
        list.move(Lifecycle.State.STARTED)
        val contacts = Owner().apply { move(Lifecycle.State.RESUMED) }
        registry.publish(contacts, model("Contacts add"), base = true)
        assertNull(registry.current)
        registry.preferBase = true
        assertEquals("Contacts add", registry.current?.actions?.single()?.id)
    }

    @Test
    fun `an ineligible still-resumed publisher retains updates without exposing them`() {
        val registry = SideChromeRegistry()
        val list = Owner().apply { move(Lifecycle.State.RESUMED) }
        val entry = registry.publish(list, model("add"), base = true)!!
        entry.setEligible(false)
        entry.update(model("proxy"))
        assertNull(registry.current)
        entry.setEligible(true)
        assertEquals("proxy", registry.current?.actions?.single()?.id)
        list.move(Lifecycle.State.STARTED)
        assertNull("uncovering must not resume a hidden tab", registry.current)
    }

    @Test
    fun `updates replace the model and destroyed pages leave`() {
        val registry = SideChromeRegistry()
        val page = Owner().apply { move(Lifecycle.State.RESUMED) }
        val entry = registry.publish(page, SideChromeModel())
        entry?.update(SideChromeModel(back = back))
        assertEquals(back, registry.current?.back)

        page.move(Lifecycle.State.DESTROYED)
        assertNull(registry.current)
    }

    @Test
    fun `a page that never started does not show`() {
        val registry = SideChromeRegistry()
        val page = Owner().apply { move(Lifecycle.State.CREATED) }
        registry.publish(page, model("early"))
        assertNull(registry.current)
    }
}
