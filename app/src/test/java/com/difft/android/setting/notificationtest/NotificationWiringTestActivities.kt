package com.difft.android.setting.notificationtest

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.R
import com.difft.android.base.utils.DualPaneHost
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.dualpane.DetailTabHostFragment
import com.difft.android.dualpane.PaneNavigator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.difft.android.base.R as BaseR


@AndroidEntryPoint
class NotificationPaneWiringTestActivity : FragmentActivity(), PaneNavigator.Owner, DualPaneHost {
    lateinit var navigator: PaneNavigator
        private set
    override val paneNavigator: PaneNavigator get() = navigator
    override val isDualPaneMode: Boolean get() = navigator.isDualPane
    override val homeTabBarObstructionPx: StateFlow<Int> = MutableStateFlow(0)
    override fun showDetailFragment(fragment: Fragment, tag: String?) = Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_index)
        navigator = PaneNavigator(
            this,
            resources.getBoolean(R.bool.dual_pane_layout_active),
            object : PaneNavigator.Pages {
                override fun conversationRoot(target: ConversationTarget): Fragment = Fragment()
                override fun usesSettingsBackground(page: Fragment): Boolean = page is SettingsIdiomPage
                override fun conversationLayerBackground(context: Context, layer: View): Drawable = ColorDrawable(0)
            },
            object : PaneNavigator.Listener {
                override fun onDetailStateChanged() = Unit
                override fun onCurrentDetailEmptied() = Unit
                override fun onSelectTabRequested(tab: Int) { navigator.selectTab(tab) }
            },
        )
        navigator.attach(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        navigator.saveState(outState)
    }

    fun notificationPage(): NotificationTestFragment =
        (supportFragmentManager.findFragmentByTag(PaneNavigator.tagFor(PaneNavigator.TAB_ME)) as DetailTabHostFragment)
            .pages().filterIsInstance<NotificationTestFragment>().single()
}


@AndroidEntryPoint
class NotificationPlainWiringTestActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = BaseR.id.detail_shell_container })
    }
}
