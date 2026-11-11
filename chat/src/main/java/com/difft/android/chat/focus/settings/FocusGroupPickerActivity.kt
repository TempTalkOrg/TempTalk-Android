package com.difft.android.chat.focus.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.addCallback
import com.difft.android.base.BaseActivity
import com.difft.android.base.R as BaseR
import com.difft.android.chat.focus.FocusActionLauncher
import com.difft.android.chat.focus.invalid.FocusInvalidController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject







@AndroidEntryPoint
class FocusGroupPickerActivity : BaseActivity(), FocusGroupPickerHost {

    @Inject
    lateinit var launcher: FocusActionLauncher

    @Inject
    lateinit var invalidController: FocusInvalidController

    private val reselectEpisode: String? get() = intent.getStringExtra(EXTRA_RESELECT_EPISODE)

    
    private var reselectAnswered = false

    
    override fun participatesInSideChrome(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply {
            id = BaseR.id.detail_shell_container
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(BaseR.id.detail_shell_container, FocusGroupPickerFragment())
                .commit()
        }
        
        
        if (reselectEpisode != null) {
            launcher.beginReselect()
            onBackPressedDispatcher.addCallback(this) {
                (supportFragmentManager.findFragmentById(BaseR.id.detail_shell_container) as? FocusGroupPickerFragment)?.cancel()
            }
        }
    }

    override fun onPickerFinished(gid: String?) {
        reselectEpisode?.let { completeReselect(it, gid) }
        finish()
    }

    
    private fun completeReselect(episode: String, gid: String?) {
        reselectAnswered = true
        if (gid == null) {
            invalidController.markDialogHandled(episode).invokeOnCompletion { launcher.endReselect() }
        } else {
            launcher.applyReselectedGroup(gid)
        }
    }

    override fun onDestroy() {
        
        if (isFinishing && reselectEpisode != null && !reselectAnswered) launcher.endReselect()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_RESELECT_EPISODE = "focus_reselect_episode"

        
        fun reselectIntent(context: Context, episode: String): Intent =
            Intent(context, FocusGroupPickerActivity::class.java).putExtra(EXTRA_RESELECT_EPISODE, episode)
    }
}
