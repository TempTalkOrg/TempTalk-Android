package com.difft.android.setting.notificationtest

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.R
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.DetailShellActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Compatibility shell; normal Me navigation uses NotificationTestDestination. */
@AndroidEntryPoint
class NotificationTestActivity : DetailShellActivity() {
    @Inject lateinit var returns: NotificationTestReturns

    override fun createPage(): Fragment = NotificationTestFragment.newInstance(shell = true)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) NotificationTestReturnRoute.read(intent)?.let { route ->
            lifecycleScope.launch { discardStale(route) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val route = NotificationTestReturnRoute.read(intent) ?: return
        // A stale return never restarts a timer or takes ownership of an unrelated live run.
        val pages = supportFragmentManager.fragments
        if (returns.decide(route, pages) is NotificationTestReturns.Decision.Existing) return
        replaceIdleAfterStale(route, pages.filterIsInstance<NotificationTestFragment>().firstOrNull())
    }

    private suspend fun discardStale(route: NotificationTestReturnRoute) {
        if (!returns.discardStale(route)) com.difft.android.base.widget.ToastUtil.show(com.difft.android.chat.R.string.notification_test_persistence_failed)
    }

    private fun replaceIdleAfterStale(route: NotificationTestReturnRoute, originalPage: NotificationTestFragment?) {
        lifecycleScope.launch {
            discardStale(route)
            if (isFinishing || supportFragmentManager.isStateSaved) return@launch
            val current = supportFragmentManager.findFragmentById(R.id.detail_shell_container)
            if (current !== originalPage || (current as? NotificationTestFragment)?.hasActiveRun() == true) return@launch
            supportFragmentManager.beginTransaction()
                .replace(R.id.detail_shell_container, NotificationTestFragment.newInstance(shell = true)).commit()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, NotificationTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun start(activity: Activity) { activity.startActivity(createIntent(activity)) }
    }
}

object NotificationTestDestination : DetailDestination {
    override fun newFragment(): Fragment = NotificationTestFragment.newInstance()
    override fun shellIntent(context: Context): Intent = NotificationTestActivity.createIntent(context)
}
