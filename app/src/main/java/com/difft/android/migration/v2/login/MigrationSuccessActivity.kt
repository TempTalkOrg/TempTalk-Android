package com.difft.android.migration.v2.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import com.difft.android.IndexActivity
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import dagger.hilt.android.AndroidEntryPoint








@AndroidEntryPoint
class MigrationSuccessActivity : BaseActivity() {

    
    private val credentialHandoff: Boolean by lazy {
        intent.getBooleanExtra(EXTRA_CREDENTIAL_HANDOFF, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.i { "[MigrationV2][Success] showing the migration-complete page" }
        setContent {
            DifftTheme {
                BackHandler { enterApp() }
                MigrationSuccessScreen(onStart = ::enterApp, credentialHandoff = credentialHandoff)
            }
        }
    }

    private fun enterApp() {
        startActivity(
            Intent(this, IndexActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    companion object {
        private const val EXTRA_CREDENTIAL_HANDOFF = "extra_credential_handoff"

        fun createIntent(context: Context, credentialHandoff: Boolean = false): Intent =
            Intent(context, MigrationSuccessActivity::class.java)
                .putExtra(EXTRA_CREDENTIAL_HANDOFF, credentialHandoff)
    }
}
