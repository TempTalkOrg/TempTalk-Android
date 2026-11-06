package com.difft.android.dualpane

import android.os.Bundle
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint
import com.difft.android.base.R as BaseR

/** Uses the production shell and request launchers, with unrelated orientation behavior disabled. */
abstract class ShellOriginProtocolFixtureActivity : DetailShellActivity() {
    var finishCalls = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)
    }

    override fun createPage(): Fragment = Fragment()
    override fun shouldApplyOrientationPolicy(): Boolean = false
    override fun shouldEnableEdgeToEdge(): Boolean = false
    override fun finish() {
        finishCalls++
        super.finish()
    }
}

@AndroidEntryPoint
class ShellOriginParentTestActivity : ShellOriginProtocolFixtureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val restore = beforeShellCreate
        beforeShellCreate = null
        restore?.invoke(this)
        super.onCreate(savedInstanceState)
    }

    companion object {
        /** Public registry-state seeding for the synchronous restored-result registration window. */
        var beforeShellCreate: ((ShellOriginParentTestActivity) -> Unit)? = null
    }
}

@AndroidEntryPoint
class ShellOriginChildTestActivity : ShellOriginProtocolFixtureActivity()

/** A real Hilt Activity that deliberately does not participate in the shell result protocol. */
@AndroidEntryPoint
class ShellOriginPlainTestActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = BaseR.id.detail_shell_container })
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(BaseR.id.detail_shell_container, Fragment()).commit()
        }
    }
}
