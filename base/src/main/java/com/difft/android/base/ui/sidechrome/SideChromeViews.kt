package com.difft.android.base.ui.sidechrome

import androidx.activity.ComponentActivity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.runtime.snapshotFlow
import androidx.fragment.app.Fragment
import com.difft.android.base.widget.floatAsTopBarButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.WeakHashMap
import kotlin.math.roundToInt











class SideChromeViewBar internal constructor(
    private val controller: SideChromeController?,
    owner: LifecycleOwner,
    base: Boolean,
) {
    private class Slot(val view: View) {
        @DrawableRes var icon = 0
        var description = ""
        var visible = false
        var enabled = true
        var tinted = true
        var floatInPlace = true
        var onClick: () -> Unit = {}
    }

    private var backButton: View? = null
    private var backVisible = false
    private var backAction: (() -> Unit)? = null
    @DrawableRes private var backIcon = 0
    private val slots = LinkedHashMap<String, Slot>()
    private val entry = controller?.registry?.publish(owner, SideChromeModel(), base)
    private var eligible = true

    
    fun setEligible(eligible: Boolean) {
        if (this.eligible == eligible) return
        this.eligible = eligible
        entry?.setEligible(eligible)
        render()
    }

    
    private var savedWidth: Int? = null
    private var savedPadding: IntArray? = null
    private var savedMarginStart = 0

    private var placementListener: ((onBar: Boolean, shortWindow: Boolean) -> Unit)? = null

    init {
        if (controller != null) {
            snapshotFlow { controller.active to controller.shortWindow }.onEach { render() }.launchIn(owner.lifecycleScope)
        }
    }

    
    fun setBack(button: View, action: (() -> Unit)?, @DrawableRes icon: Int = 0) {
        backButton = button
        backVisible = action != null
        backAction = action
        backIcon = icon
        if (action != null) button.setOnClickListener { action() }
        render()
    }

    




    fun setAction(
        id: String,
        view: View,
        @DrawableRes icon: Int,
        description: String,
        visible: Boolean,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
        tinted: Boolean = true,
        floatInPlace: Boolean = true,
    ) {
        val slot = slots.getOrPut(id) { Slot(view) }
        slot.floatInPlace = floatInPlace
        slot.icon = icon
        slot.description = description
        slot.visible = visible
        slot.enabled = enabled
        slot.tinted = tinted
        if (onClick != null) {
            slot.onClick = onClick
            view.setOnClickListener { onClick() }
        }
        render()
    }

    





    fun onPlacement(listener: (onBar: Boolean, shortWindow: Boolean) -> Unit) {
        placementListener = listener
        listener(controller?.active == true, controller?.shortWindow == true)
    }

    



    fun barBoundsInWindow(id: String): Rect? = controller?.takeIf { it.active }?.buttonBoundsInWindow(id)

    






    private fun placeBack(button: View, onBar: Boolean) {
        if (backVisible && eligible && onBar) {
            if (savedWidth == null) {
                savedWidth = button.layoutParams.width
                savedPadding = intArrayOf(button.paddingLeft, button.paddingTop, button.paddingRight, button.paddingBottom)
                savedMarginStart = (button.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
            }
            button.layoutParams = button.layoutParams.apply {
                width = (PLACEHOLDER_DP * button.resources.displayMetrics.density).roundToInt()
                (this as? ViewGroup.MarginLayoutParams)?.marginStart = 0
            }
            button.setPadding(0, button.paddingTop, 0, button.paddingBottom)
            button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            button.visibility = View.INVISIBLE
        } else {
            savedWidth?.let { width ->
                button.layoutParams = button.layoutParams.apply {
                    this.width = width
                    (this as? ViewGroup.MarginLayoutParams)?.marginStart = savedMarginStart
                }
            }
            savedPadding?.let { (left, top, right, bottom) -> button.setPadding(left, top, right, bottom) }
            savedWidth = null
            savedPadding = null
            button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            button.visibility = if (backVisible && eligible) View.VISIBLE else View.GONE
        }
    }

    private val floated = HashSet<View>()

    private fun floatOnce(view: View, leading: Boolean) {
        if (view !is ImageView || !floated.add(view)) return
        view.floatAsTopBarButton(leading)
    }

    private fun render() {
        val onBar = controller?.active == true
        backButton?.let {
            floatOnce(it, leading = true)
            placeBack(it, onBar)
        }
        slots.values.forEach { slot ->
            slot.view.visibility = if (eligible && slot.visible && !onBar) View.VISIBLE else View.GONE
            slot.view.isEnabled = eligible && slot.enabled
            if (slot.floatInPlace) floatOnce(slot.view, leading = false)
        }
        placementListener?.invoke(onBar, controller?.shortWindow == true)
        entry?.update(
            SideChromeModel(
                back = backAction.takeIf { backVisible },
                backIcon = backIcon,
                actions = slots.entries.filter { it.value.visible }.map { (id, slot) ->
                    RailAction(id = id, icon = slot.icon, contentDescription = slot.description, enabled = slot.enabled, tinted = slot.tinted, onClick = slot.onClick)
                },
            )
        )
    }
}

private const val PLACEHOLDER_DP = 16





fun ComponentActivity.sideChromeBar(): SideChromeViewBar = bars.getOrPut(this) {
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) bars.remove(this)
    })
    SideChromeViewBar(SideChrome.of(this), this, base = false)
}

private val bars = WeakHashMap<LifecycleOwner, SideChromeViewBar>()






fun Fragment.sideChromeBar(base: Boolean = false): SideChromeViewBar {
    val owner = viewLifecycleOwner
    return bars.getOrPut(owner) {
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) bars.remove(owner)
        })
        SideChromeViewBar(activity?.takeUnless { keepsOwnChrome() }?.let(SideChrome::of), owner, base)
    }
}
