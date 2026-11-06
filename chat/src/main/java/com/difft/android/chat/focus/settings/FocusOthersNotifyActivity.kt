package com.difft.android.chat.focus.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.focus.showFocusError
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** "Other Groups Still Notify": which items still notify from non-focused groups while focus is on. */
@AndroidEntryPoint
class FocusOthersNotifyActivity : BaseActivity() {

    private val viewModel: FocusOthersNotifyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DifftTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                StillNotifyScreen(
                    title = getString(R.string.focus_others_still_notify),
                    state = state.items,
                    onToggle = { item, on -> viewModel.dispatch(FocusOthersNotifyContract.Intent.Toggle(item, on)) },
                    onBack = { finish() },
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        FocusOthersNotifyContract.Effect.ShowError -> showFocusError()
                    }
                }
            }
        }
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, FocusOthersNotifyActivity::class.java))
        }
    }
}
