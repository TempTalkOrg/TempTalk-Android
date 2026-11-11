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











@AndroidEntryPoint
class MigrationHandoffEntryActivity : BaseActivity() {

    private val viewModel: MigrationHandoffEntryViewModel by viewModels()

    
    private var receiveStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        
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
            
            
            MigrationTransferService.cancel(this)
        }
        super.onDestroy()
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, MigrationHandoffEntryActivity::class.java)
    }
}
