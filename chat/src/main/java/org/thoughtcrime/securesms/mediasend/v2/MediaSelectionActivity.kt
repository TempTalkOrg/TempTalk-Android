package org.thoughtcrime.securesms.mediasend.v2

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import com.difft.android.base.BaseActivity
import com.difft.android.chat.R
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.kongzue.dialogx.interfaces.DialogLifecycleCallback
import com.luck.picture.lib.entity.LocalMedia
import util.getParcelableArrayListExtraCompat
import util.logging.Log
import org.thoughtcrime.securesms.mediasend.MediaSendActivityResult
import org.thoughtcrime.securesms.mediasend.v2.review.MediaReviewFragment
import org.thoughtcrime.securesms.util.FullscreenHelper
import org.thoughtcrime.securesms.util.WindowUtil

class MediaSelectionActivity : BaseActivity(), MediaReviewFragment.Callback {

    companion object {
        private val TAG = Log.tag(MediaSelectionActivity::class.java)

        const val MEDIA = "media"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.media_selection_activity)

        FullscreenHelper.showSystemUI(window)
        WindowUtil.setNavigationBarColor(this, 0x01000000)
        WindowUtil.setStatusBarColor(window, Color.TRANSPARENT)

        val initialMedia: List<LocalMedia> = intent.getParcelableArrayListExtraCompat(MEDIA, LocalMedia::class.java) ?: listOf()

        val factory = MediaSelectionViewModel.Factory(initialMedia, MediaSelectionRepository(this))
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

    override fun onSendError(error: Throwable) {
        TipDialog.show(R.string.operation_failed, WaitDialog.TYPE.WARNING, 3000)
            .dialogLifecycleCallback = object : DialogLifecycleCallback<WaitDialog?>() {
            override fun onDismiss(dialog: WaitDialog?) {
                setResult(RESULT_CANCELED)
                finish()
            }
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
