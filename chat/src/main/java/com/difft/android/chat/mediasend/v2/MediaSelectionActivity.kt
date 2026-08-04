package com.difft.android.chat.mediasend.v2

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import com.difft.android.base.BaseActivity
import com.difft.android.chat.R
import com.difft.android.selector.entity.LocalMedia
import util.getParcelableArrayListExtraCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.mediasend.MediaFailureClassifier
import com.difft.android.chat.mediasend.MediaSendActivityResult
import com.difft.android.chat.mediasend.MediaSendFailureNotice
import com.difft.android.chat.mediasend.v2.review.MediaReviewFragment
import com.difft.android.chat.util.FullscreenHelper
import com.difft.android.chat.util.WindowUtil

class MediaSelectionActivity : BaseActivity(), MediaReviewFragment.Callback {

    // Disable auto padding - this Activity uses fullscreen layout with FullscreenHelper
    override fun shouldApplySystemBarsPadding(): Boolean = false

    companion object {
        private val TAG = L.tag(MediaSelectionActivity::class.java)

        const val MEDIA = "media"
        const val EXTRA_CONFIDENTIAL_MODE = "confidential_mode"
        const val EXTRA_SHOW_CONFIDENTIAL_TOGGLE = "show_confidential_toggle"
        const val EXTRA_CONVERSATION_ID = "conversation_id"

        fun startActivity(activity: Context, media: List<LocalMedia>) {
            val intent = Intent(activity, MediaSelectionActivity::class.java).apply {
                putParcelableArrayListExtra(MEDIA, ArrayList(media))
            }
            activity.startActivity(intent)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        super.attachBaseContext(newBase)
    }

    lateinit var viewModel: MediaSelectionViewModel
    var conversationId: String = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.media_selection_activity)

        FullscreenHelper.showSystemUI(window)
        WindowUtil.setNavigationBarColor(this, 0x01000000)
        WindowUtil.setStatusBarColor(window, Color.TRANSPARENT)

        val initialMedia: List<LocalMedia> = intent.getParcelableArrayListExtraCompat(MEDIA, LocalMedia::class.java) ?: listOf()
        val confidentialMode = intent.getIntExtra(EXTRA_CONFIDENTIAL_MODE, 0)
        val showConfidentialToggle = intent.getBooleanExtra(EXTRA_SHOW_CONFIDENTIAL_TOGGLE, false)
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: ""

        val factory = MediaSelectionViewModel.Factory(initialMedia, MediaSelectionRepository(this), confidentialMode, showConfidentialToggle)
        viewModel = ViewModelProvider(this, factory)[MediaSelectionViewModel::class.java]

//        onBackPressedDispatcher.addCallback(OnBackPressed())
    }

//    private inner class OnBackPressed : OnBackPressedCallback(true) {
//        override fun handleOnBackPressed() {
//            finish()
//        }
//    }

    override fun onSentWithResult(mediaSendActivityResult: MediaSendActivityResult) {
        setResult(RESULT_OK, Intent().apply { putExtra(MediaSendActivityResult.EXTRA_RESULT, mediaSendActivityResult) })
        finish()
    }

    override fun onSentWithoutResult() {
        val intent = Intent()
        setResult(RESULT_OK, intent)
        finish()
    }

    /**
     * Stays on the review screen: finishing here discarded the typed caption and the whole selection
     * to report a failure, and the throwable itself was dropped without a trace.
     */
    override fun onSendError(error: Throwable) {
        val failure = MediaFailureClassifier.classifyThrown(error)
        MediaSendFailureNotice.showThrown(this, failure) {
            (supportFragmentManager.findFragmentById(R.id.fragment_container) as? MediaReviewFragment)
                ?.retrySend()
        }
    }

    override fun onNoMediaSelected() {
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onPopFromReview() {
        finish()
    }
}
