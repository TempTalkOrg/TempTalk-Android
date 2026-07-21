package com.difft.android.linkeddevices

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.theme.DifftTheme
import dagger.hilt.android.AndroidEntryPoint

/** Phone fallback host for the Linked Devices screen. Tablet uses [LinkedDevicesFragment]. */
@AndroidEntryPoint
class LinkedDevicesActivity : BaseActivity() {

    companion object {
        fun startActivity(context: Context) =
            context.startActivity(Intent(context, LinkedDevicesActivity::class.java))
    }

    private val viewModel: LinkedDevicesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DifftTheme {
                LinkedDevicesScreen(viewModel, showBackButton = true, onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopExpecting() // stop the expect-more poll timer while off-screen
    }
}
