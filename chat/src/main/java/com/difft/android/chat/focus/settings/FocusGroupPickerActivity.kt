package com.difft.android.chat.focus.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.focus.FocusActionLauncher
import com.difft.android.chat.focus.invalid.FocusInvalidController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Select Focus Group": returns the chosen gid through [Contract], or null when cancelled. Launched
 * with [reselectIntent] (invalid focus, D5 / D5b) it completes the answer itself: the pick becomes the
 * focus group, a cancel marks the dialog handled; no result callback has to survive a host recreation.
 */
@AndroidEntryPoint
class FocusGroupPickerActivity : BaseActivity() {

    private val viewModel: FocusGroupPickerViewModel by viewModels()

    @Inject
    lateinit var launcher: FocusActionLauncher

    @Inject
    lateinit var invalidController: FocusInvalidController

    private val reselectEpisode: String? get() = intent.getStringExtra(EXTRA_RESELECT_EPISODE)

    /** Reselect mode: whether the picker delivered its answer (pick or cancel). */
    private var reselectAnswered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DifftTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                FocusGroupPickerScreen(
                    state = state,
                    onQueryChange = { viewModel.dispatch(FocusGroupPickerContract.Intent.QueryChanged(it)) },
                    onSelect = { viewModel.dispatch(FocusGroupPickerContract.Intent.Select(it)) },
                    onCancel = { viewModel.dispatch(FocusGroupPickerContract.Intent.Cancel) },
                    onDone = { viewModel.dispatch(FocusGroupPickerContract.Intent.Done) },
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect { effect ->
                    when (effect) {
                        is FocusGroupPickerContract.Effect.Finish -> {
                            val episode = reselectEpisode
                            if (episode != null) {
                                completeReselect(episode, effect.gid)
                            } else if (effect.gid != null) {
                                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_GID, effect.gid))
                            } else {
                                setResult(Activity.RESULT_CANCELED)
                            }
                            finish()
                        }
                    }
                }
            }
        }
        // Reselect mode: the system Back is a cancel too, so the answer is always recorded. The guard is
        // (re)armed here as well: a picker Android restores after a process death starts without it.
        if (reselectEpisode != null) {
            launcher.beginReselect()
            onBackPressedDispatcher.addCallback(this) { viewModel.dispatch(FocusGroupPickerContract.Intent.Cancel) }
        }
        viewModel.dispatch(FocusGroupPickerContract.Intent.Load)
    }

    /** Both run detached from this page, so finishing right after is safe; each ends the reselect guard when done. */
    private fun completeReselect(episode: String, gid: String?) {
        reselectAnswered = true
        if (gid == null) {
            invalidController.markDialogHandled(episode).invokeOnCompletion { launcher.endReselect() }
        } else {
            launcher.applyReselectedGroup(gid)
        }
    }

    override fun onDestroy() {
        // Finished without an answer (not a recreation): release the guard so the dialog can return.
        if (isFinishing && reselectEpisode != null && !reselectAnswered) launcher.endReselect()
        super.onDestroy()
    }

    /** Launch with `Unit`; the result is the chosen gid, or null when the user cancelled. */
    class Contract : ActivityResultContract<Unit, String?>() {
        override fun createIntent(context: Context, input: Unit): Intent = Intent(context, FocusGroupPickerActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(EXTRA_GID) else null
    }

    companion object {
        private const val EXTRA_GID = "focus_group_gid"
        private const val EXTRA_RESELECT_EPISODE = "focus_reselect_episode"

        /** Reselect after an invalid focus: the picker applies the pick or records the cancel for [episode]. */
        fun reselectIntent(context: Context, episode: String): Intent =
            Intent(context, FocusGroupPickerActivity::class.java).putExtra(EXTRA_RESELECT_EPISODE, episode)
    }
}
