package com.difft.android.chat.invite

import android.animation.ObjectAnimator
import com.difft.android.base.widget.floatAsTopBarButton
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.dp
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.common.LinearProgressBar
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.qr.QrEncoder
import com.difft.android.chat.qr.QrErrorCorrection
import com.difft.android.chat.ui.ChatBackgroundDrawable
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.difft.android.base.widget.ComposeDialogManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.difft.android.base.widget.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.chat.util.Util
import com.difft.android.chat.util.shareText
import javax.inject.Inject

class InviteUtils @Inject constructor() {

    @Inject
    lateinit var inviteRepo: InviteRepo

    fun showInviteDialog(context: FragmentActivity) {
        val fragment = InviteBottomSheetFragment()
        fragment.show(context.supportFragmentManager, "InviteDialog")
    }

    private val maxAutoRefreshTimes = 3
    private var currentAutoRefreshTimes = 0

    fun getInviteCode(
        context: FragmentActivity,
        regenerate: Int,
        short: Int,
        imageViewQR: AppCompatImageView?,
        clShare: ConstraintLayout?,
        clCopy: ConstraintLayout?,
        tvCode: AppCompatTextView?,
        progressBar: LinearProgressBar?,
        tvError: AppCompatTextView?,
        onCopyDismiss: (() -> Unit)? = null
    ) {
        if (regenerate == 0 && currentAutoRefreshTimes >= maxAutoRefreshTimes) { 
            return
        }
        ComposeDialogManager.showWait(context, "")
        context.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    inviteRepo.getInviteCode(regenerate, short)
                }
                ComposeDialogManager.dismissWait()
                if (result.status == 0) {
                    currentAutoRefreshTimes++

                    result.data?.inviteLink?.let { url ->
                        val bmp = createQRBitmap(context, url)   
                        imageViewQR?.setImageBitmap(bmp)         

                        val content = context.getString(R.string.invite_tips, url)
                        clShare?.setOnClickListener {
                            context.shareText(content)
                        }

                        clCopy?.setOnClickListener {
                            Util.copyToClipboard(context, content)
                            onCopyDismiss?.invoke()
                        }
                    }

                    result.data?.randomCode?.let { code ->
                        tvCode ?: return@let
                        tvCode.text = code

                        if (code.contains("*")) {
                            tvError?.visibility = View.VISIBLE
                            tvError?.text = context.getString(R.string.invite_code_error_tips)
                        } else {
                            tvError?.visibility = View.GONE
                        }
                    }

                    startCountdown(
                        context,
                        (result.data?.randomCodeExpiration?.toLong() ?: 0) * 1000,
                        (result.data?.randomCodeTTL?.toLong() ?: 0) * 1000,
                        progressBar
                    ) {
                        getInviteCode(context, 0, 0, imageViewQR, clShare, clCopy, tvCode, progressBar, tvError)
                    }
                } else {
                    result.reason?.let { message -> ToastUtil.show(message) }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                L.e { "getInviteCode error: ${e.stackTraceToString()}" }
                ToastUtil.show(context.getString(R.string.chat_net_error))
            }
        }
    }

    private var scaleXAnimator: ObjectAnimator? = null
    private var scaleYAnimator: ObjectAnimator? = null

    fun startCodeTextAnimation(tvCode: AppCompatTextView) {
        scaleXAnimator = ObjectAnimator.ofFloat(tvCode, "scaleX", 0.6f, 1f)
        scaleYAnimator = ObjectAnimator.ofFloat(tvCode, "scaleY", 0.6f, 1f)

        val duration = 1000L
        scaleXAnimator?.duration = duration
        scaleYAnimator?.duration = duration

        scaleXAnimator?.repeatCount = ValueAnimator.INFINITE
        scaleYAnimator?.repeatCount = ValueAnimator.INFINITE
        scaleXAnimator?.repeatMode = ValueAnimator.REVERSE
        scaleYAnimator?.repeatMode = ValueAnimator.REVERSE

        scaleXAnimator?.interpolator = LinearInterpolator()
        scaleYAnimator?.interpolator = LinearInterpolator()

        scaleXAnimator?.start()
        scaleYAnimator?.start()
    }

    private fun cancelCodeTextAnimation() {
        if (scaleXAnimator?.isRunning == true) {
            scaleXAnimator?.cancel()
            scaleXAnimator = null
        }
        if (scaleYAnimator?.isRunning == true) {
            scaleYAnimator?.cancel()
            scaleYAnimator = null
        }
    }

    private var countdownJob: kotlinx.coroutines.Job? = null

    




    private fun startCountdown(
        activity: FragmentActivity,
        maxTime: Long,
        remainingTime: Long,
        progressBar: LinearProgressBar?,
        onCountdownComplete: (() -> Unit)?
    ) {
        countdownJob?.cancel()

        val interval = 100L
        val maxProgress = 100

        countdownJob = activity.lifecycleScope.launch {
            progressBar?.setProgress((remainingTime.toFloat() / maxTime * maxProgress))

            var elapsed = 0L
            while (elapsed * interval <= remainingTime) {
                val currentTime = remainingTime - elapsed * interval
                val progress = (currentTime.toFloat() / maxTime * maxProgress)
                progressBar?.setProgress(progress)
                elapsed++
                kotlinx.coroutines.delay(interval)
            }

            onCountdownComplete?.invoke()
        }
    }

    
    
    private suspend fun createQRBitmap(context: Activity, url: String): Bitmap? =
        withContext(Dispatchers.Default) {
            val logoBitmap = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_invite_qr_logo)?.let {
                val bitmap = createBitmap(it.intrinsicWidth, it.intrinsicHeight)
                val canvas = android.graphics.Canvas(bitmap)
                it.setBounds(0, 0, canvas.width, canvas.height)
                it.draw(canvas)
                bitmap
            }
            QrEncoder.encode(
                content = url,
                sizePx = 200.dp,
                foreground = ContextCompat.getColor(context, com.difft.android.base.R.color.bg2_night),
                errorCorrection = QrErrorCorrection.HIGH,   
                logo = logoBitmap,
            )
        }

    fun queryByInviteCode(activity: FragmentActivity, inviteCode: String, needFinish: Boolean = false) {
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    inviteRepo.queryByInviteCode(inviteCode)
                }
                if (result.status == 0) {
                    result.data?.uid?.let { uid ->
                        ContactDetailActivity.startActivity(activity, uid)
                        if (needFinish) {
                            activity.finish()
                        }
                    }
                } else {
                    showErrorAndFinish(result.reason ?: "", activity, needFinish)
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "queryByInviteCode error: ${e.stackTraceToString()}" }
                showErrorAndFinish(activity.getString(R.string.chat_net_error), activity, needFinish)
            }
        }
    }

    private fun showErrorAndFinish(tips: String, activity: Activity, needFinish: Boolean = false) {
        ToastUtil.showLong(tips)
        if (needFinish) {
            activity.finish()
        }
    }

    fun onInviteDialogDismiss() {
        countdownJob?.cancel()
        countdownJob = null
        cancelCodeTextAnimation()
        currentAutoRefreshTimes = 0
    }

}

@AndroidEntryPoint
class InviteBottomSheetFragment() : BaseBottomSheetDialogFragment() {

    @Inject
    lateinit var inviteUtils: InviteUtils

    override fun getContentLayoutResId(): Int = R.layout.layout_invite

    override fun isFullScreen(): Boolean = true

    
    override fun showDragHandle(): Boolean = false

    
    override fun useDefaultBackground(): Boolean = false

    override fun onBottomSheetReady(sheet: View, behavior: BottomSheetBehavior<*>) {
        super.onBottomSheetReady(sheet, behavior)
        sheet.background = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        view.background = ChatBackgroundDrawable(requireContext(), view, false)
    }

    override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {

        var ivQR: AppCompatImageView? = null
        var clShare: ConstraintLayout? = null
        var clCopy: ConstraintLayout? = null
        var tvCode: AppCompatTextView? = null
        var progressBar: LinearProgressBar? = null
        var tvError: AppCompatTextView? = null

        ivQR = view.findViewById(R.id.iv_QR)
        clShare = view.findViewById(R.id.cl_share)
        clCopy = view.findViewById(R.id.cl_copy)
        tvCode = view.findViewById(R.id.tv_code)
        tvCode?.let {
            inviteUtils.startCodeTextAnimation(it)
        }
        progressBar = view.findViewById(R.id.progressBar)
        tvError = view.findViewById(R.id.tv_error)
        val clScan: ConstraintLayout = view.findViewById(R.id.cl_scan)
        val llRegenerate = view.findViewById<AppCompatImageView>(R.id.iv_regenerate)
        val clEnterCode = view.findViewById<ConstraintLayout>(R.id.cl_enter_code)

        val avatarView: AvatarView = view.findViewById(R.id.imageview_avatar)
        val tvName: AppCompatTextView = view.findViewById(R.id.tv_name)
        val tvContent: AppCompatTextView = view.findViewById(R.id.tv_content)
        InviteQrFit.attach(view)

        lifecycleScope.launch {
            val myInfo = withContext(Dispatchers.IO) {
                ContactorUtil.getContactWithID(requireContext(), globalServices.myId)
            }

            myInfo.ifPresent { contact ->
                avatarView.setAvatar(contact)
                tvName.text = contact.getDisplayNameForUI()
                tvContent.text = requireContext().getString(R.string.invite_joined_at, contact.joinedAt)
            }
        }

        llRegenerate.visibility = View.VISIBLE
        llRegenerate.setOnClickListener {
            ComposeDialogManager.showMessageDialog(
                context = requireActivity(),
                title = getString(R.string.invite_regenerate_dialog_title),
                message = getString(R.string.invite_regenerate_dialog_content),
                confirmText = getString(R.string.invite_regenerate_dialog_ok),
                cancelText = getString(R.string.invite_regenerate_dialog_cancel),
                onConfirm = {
                    inviteUtils.getInviteCode(requireActivity(), 1, 0, ivQR, clShare, clCopy, tvCode, progressBar, tvError)
                }
            )
        }

        clScan.setOnClickListener {
            ScanActivity.startActivity(requireActivity())
        }

        view.findViewById<AppCompatImageView>(R.id.iv_close).apply { floatAsTopBarButton(leading = false) }.setOnClickListener {
            dismiss()
        }

        clEnterCode.setOnClickListener {
            dismiss()
            InviteCodeActivity.startActivity(requireActivity())
        }

        
        view.post {
            if (isAdded && context != null) {
                inviteUtils.getInviteCode(requireActivity(), 0, 0, ivQR, clShare, clCopy, tvCode, progressBar, tvError)
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        inviteUtils.onInviteDialogDismiss()
    }
}

