package com.difft.android.migration.v2.handoff

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.login.LoginActivity
import com.difft.android.migration.v2.login.MigrationImportLauncherImpl
import com.difft.android.migration.v2.transfer.MigrationTransferService
import com.difft.android.migration.v2.transfer.login.MigrationP2pReceiveActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The new package's first-launch entry into the same-device no-login migration.
 *
 * Reached two ways: `MainActivity`'s startup probe routes here instead of the login page when the old
 * app holds a session that can travel, or the old app's guide page opens it through the exported
 * trampoline with [PeerApps.EXTRA_AUTO_START], meaning the choice was already made over there.
 *
 * Every exit stops the receiver, including a system-driven `onDestroy`; otherwise the foreground
 * service keeps listening on loopback while the user signs in normally.
 */
@AndroidEntryPoint
class MigrationHandoffEntryActivity : BaseActivity() {

    private val viewModel: MigrationHandoffEntryViewModel by viewModels()

    /** True once the receiver has been started, so the teardown in [onDestroy] must not fire. */
    private var receiveStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The account material this page is about to move is sensitive; keep it out of recents
        // thumbnails and screen recordings, exactly like the transfer screens.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            DifftTheme {
                val state by viewModel.state.collectAsState()
                MigrationHandoffEntryScreen(state = state, onIntent = viewModel::onIntent)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        MigrationHandoffEntryEffect.StartReceive -> startReceive()
                        MigrationHandoffEntryEffect.OpenLogin -> openLogin()
                        MigrationHandoffEntryEffect.Finish -> finish()
                    }
                }
            }
        }

        viewModel.onIntent(
            MigrationHandoffEntryIntent.Start(autoStart = intent.getBooleanExtra(PeerApps.EXTRA_AUTO_START, false)),
        )
    }

    /**
     * The receiver listens with no account of its own — the account arrives inside the container and
     * is adopted by the handshake gate. The receive screen restarts the process when it lands.
     */
    private fun startReceive() {
        receiveStarted = true
        L.i { "[MigrationV2][Handoff] starting the no-login receiver" }
        startActivity(
            Intent(this, MigrationP2pReceiveActivity::class.java)
                .putExtra(MigrationImportLauncherImpl.EXTRA_ACCOUNT, "")
                .putExtra(MigrationImportLauncherImpl.EXTRA_SAME_DEVICE, true)
                .putExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, true),
        )
        finish()
    }

    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        if (isFinishing && !receiveStarted) {
            // Belt and braces for every exit that did not go through the ViewModel (a system-driven
            // finish, a task swipe): the receiver must never outlive this path.
            MigrationTransferService.cancel(this)
        }
        super.onDestroy()
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, MigrationHandoffEntryActivity::class.java)
    }
}
