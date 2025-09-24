package com.difft.android.chat.invite

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cn.bingoogolapple.qrcode.zxing.QRCodeEncoder
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.dp
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ChativeButton
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.common.GroupAvatarView
import com.difft.android.chat.common.LinearProgressBar
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.getAvatarData
import com.difft.android.chat.ui.ChatBackgroundDrawable
import com.difft.android.network.UrlManager
import com.difft.android.network.group.GroupInfoByInviteCodeResp
import com.difft.android.network.group.GroupRepo
import com.kongzue.dialogx.dialogs.FullScreenDialog
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.PopTip
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.kongzue.dialogx.interfaces.DialogLifecycleCallback
import com.kongzue.dialogx.interfaces.OnBindView
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.util.Util
import org.thoughtcrime.securesms.util.shareText
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class InviteUtils @Inject constructor() {

    @Inject
    lateinit var urlManager: UrlManager

    @Inject
    lateinit var inviteRepo: InviteRepo

    @Inject
    lateinit var groupRepo: GroupRepo


    private var mInviteDialog: FullScreenDialog? = null

    fun showInviteDialog(context: Activity) {
        var ivQR: AppCompatImageView? = null
        var clShare: ConstraintLayout? = null
        var clCopy: ConstraintLayout? = null
        var tvCode: AppCompatTextView? = null
        var progressBar: LinearProgressBar? = null
        var tvError: AppCompatTextView? = null

        mInviteDialog = FullScreenDialog.build(object : OnBindView<FullScreenDialog>(R.layout.layout_invite) {
            override fun onBind(dialog: FullScreenDialog, v: View) {
                v.background = ChatBackgroundDrawable(context, v, false)

                ivQR = v.findViewById(R.id.iv_QR)
                clShare = v.findViewById(R.id.cl_share)
                clCopy = v.findViewById(R.id.cl_copy)
                tvCode = v.findViewById(R.id.tv_code)
                tvCode?.let {
                    startCodeTextAnimation(it)
                }
                progressBar = v.findViewById(R.id.progressBar)
                tvError = v.findViewById(R.id.tv_error)
                val clScan: ConstraintLayout = v.findViewById(R.id.cl_scan)
                val llRegenerate = v.findViewById<AppCompatImageView>(R.id.iv_regenerate)
                val clEnterCode = v.findViewById<ConstraintLayout>(R.id.cl_enter_code)

                val avatarView: AvatarView = v.findViewById(R.id.imageview_avatar)
                val tvName: AppCompatTextView = v.findViewById(R.id.tv_name)
                val tvContent: AppCompatTextView = v.findViewById(R.id.tv_content)

                (context as? LifecycleOwner)?.lifecycleScope?.launch {
                    val myInfo = withContext(Dispatchers.IO) {
                        ContactorUtil.getContactWithID(context, globalServices.myId).blockingGet()
                    }

                    myInfo.ifPresent { contact ->
                        avatarView.setAvatar(contact)
                        tvName.text = contact.getDisplayNameForUI()
                        tvContent.text = context.getString(R.string.invite_joined_at, contact.joinedAt)
                    }
                }


                llRegenerate.visibility = View.VISIBLE
                llRegenerate.setOnClickListener {
                    MessageDialog.show(R.string.invite_regenerate_dialog_title, R.string.invite_regenerate_dialog_content, R.string.invite_regenerate_dialog_ok, R.string.invite_regenerate_dialog_cancel)
                        .setOkButton { _, _ ->
                            getInviteCode(context, 1, 0, ivQR, clShare, clCopy, tvCode, progressBar, tvError)
                            false
                        }
                }

                clScan.setOnClickListener {
                    ScanActivity.startActivity(context)
                }

                v.findViewById<AppCompatImageView>(R.id.iv_close).setOnClickListener {
                    mInviteDialog?.dismiss()
                }

                clEnterCode.setOnClickListener {
                    mInviteDialog?.dismiss()
                    InviteCodeActivity.startActivity(context)
                }
            }
        })
            .setBackgroundColor(ContextCompat.getColor(context, com.difft.android.base.R.color.bg2))
            .show()

        mInviteDialog?.dialogLifecycleCallback = object : DialogLifecycleCallback<FullScreenDialog?>() {
            override fun onDismiss(dialog: FullScreenDialog?) {
                if (countdownDispose?.isDisposed == false) {
                    countdownDispose?.dispose()
                }
                cancelCodeTextAnimation()
                currentAutoRefreshTimes = 0
            }
        }

        getInviteCode(context, 0, 0, ivQR, clShare, clCopy, tvCode, progressBar, tvError)
    }

    private val maxAutoRefreshTimes = 3 //自动刷新最大次数
    private var currentAutoRefreshTimes = 0 //当前自动刷新次数

    fun getInviteCode(
        context: Activity,
        regenerate: Int,
        short: Int,
        imageViewQR: AppCompatImageView?,
        clShare: ConstraintLayout?,
        clCopy: ConstraintLayout?,
        tvCode: AppCompatTextView?,
        progressBar: LinearProgressBar?,
        tvError: AppCompatTextView?
    ) {
        if (regenerate == 0 && currentAutoRefreshTimes >= maxAutoRefreshTimes) { //强制刷新不限制次数
            return
        }
        WaitDialog.show(context, "")
        inviteRepo.getInviteCode(regenerate, short)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(context as LifecycleOwner))
            .subscribe({
                WaitDialog.dismiss()
                if (it.status == 0) {
                    currentAutoRefreshTimes++

                    it.data?.inviteLink?.let { url ->
                        imageViewQR?.setImageBitmap(createQRBitmap(context, url))

                        val content = context.getString(R.string.invite_tips, url)
                        clShare?.setOnClickListener {
                            context.shareText(content)
                        }

                        clCopy?.setOnClickListener {
                            Util.copyToClipboard(context, content)
                            mInviteDialog?.dismiss()
                        }
                    }

                    it.data?.randomCode?.let { code ->
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
                        (it.data?.randomCodeExpiration?.toLong() ?: 0) * 1000,
                        (it.data?.randomCodeTTL?.toLong() ?: 0) * 1000,
                        progressBar
                    ) {
                        getInviteCode(context, 0, 0, imageViewQR, clShare, clCopy, tvCode, progressBar, tvError)
                    }
                } else {
                    PopTip.show(it.reason)
                }
            }, {
                WaitDialog.dismiss()
                it.printStackTrace()
                L.e { "getInviteCode error: ${it.stackTraceToString()}" }
                PopTip.show(context.getString(R.string.chat_net_error))
            })
    }

    private var scaleXAnimator: ObjectAnimator? = null
    private var scaleYAnimator: ObjectAnimator? = null

    private fun startCodeTextAnimation(tvCode: AppCompatTextView) {
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

    private var countdownDispose: Disposable? = null

    /**
     * 启动倒计时
     * @param maxTime 最大时长（毫秒）
     * @param remainingTime 剩余时间（毫秒）
     */
    private fun startCountdown(
        maxTime: Long,
        remainingTime: Long,
        progressBar: LinearProgressBar?,
        onCountdownComplete: (() -> Unit)?
    ) {
        if (countdownDispose?.isDisposed == false) {
            countdownDispose?.dispose()
        }

        val interval = 100L // 每100ms更新一次
        val maxProgress = 100

        countdownDispose = Observable.interval(0, interval, TimeUnit.MILLISECONDS)
            .takeWhile { elapsed -> elapsed * interval <= remainingTime }
            .map { elapsed ->
                val currentTime = remainingTime - elapsed * interval
                (currentTime.toFloat() / maxTime * maxProgress)
            }
            .doOnSubscribe {
                // 初始化进度条为当前剩余时间的百分比
                progressBar?.setProgress((remainingTime.toFloat() / maxTime * maxProgress))
            }
            .compose(RxUtil.getSchedulerComposer())
            .doOnComplete {
                // 倒计时结束时执行任务
                onCountdownComplete?.invoke()
            }
            .subscribe { remainingProgress ->
                progressBar?.setProgress(remainingProgress)
            }
    }

    private fun createQRBitmap(context: Activity, url: String): Bitmap? {
        val logoBitmap = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_invite_qr_logo)
        val bitmap = QRCodeEncoder.syncEncodeQRCode(url, 200.dp, ContextCompat.getColor(context, com.difft.android.base.R.color.bg2_night), logoBitmap)
        return bitmap
    }

    fun queryByInviteCode(activity: Activity, inviteCode: String, needFinish: Boolean = false) {
        inviteRepo.queryByInviteCode(inviteCode)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(activity as LifecycleOwner))
            .subscribe({
                if (it.status == 0) {
                    it.data?.uid?.let { uid ->
                        ContactDetailActivity.startActivity(activity, uid)
                        if (needFinish) {
                            activity.finish()
                        }
                    }
                } else {
                    showErrorAndFinish(it.reason ?: "", activity, needFinish)
                }
            }, {
                it.printStackTrace()
                L.e { "queryByInviteCode error: ${it.stackTraceToString()}" }
                showErrorAndFinish(activity.getString(R.string.chat_net_error), activity, needFinish)
            })
    }

    private fun showErrorAndFinish(tips: String, activity: Activity, needFinish: Boolean = false) {
        TipDialog.show(tips, WaitDialog.TYPE.ERROR, 2000).dialogLifecycleCallback = object : DialogLifecycleCallback<WaitDialog?>() {
            override fun onDismiss(dialog: WaitDialog?) {
                if (needFinish) {
                    activity.finish()
                }
            }
        }
    }

    private var mGroupInviteDialog: FullScreenDialog? = null

    fun showGroupInviteDialog(
        context: Activity,
        myName: String = "",
        groupName: String = "",
        groupAvatar: String? = null,
        inviteCode: String = "",
    ) {
        var ivQR: AppCompatImageView? = null
        var clShare: ConstraintLayout? = null
        var clCopy: ConstraintLayout? = null

        mGroupInviteDialog = FullScreenDialog.build(object : OnBindView<FullScreenDialog>(R.layout.layout_group_invite) {
            override fun onBind(dialog: FullScreenDialog, v: View) {
                ivQR = v.findViewById(R.id.iv_QR)
                clShare = v.findViewById(R.id.cl_share)
                clCopy = v.findViewById(R.id.cl_copy)
                val clScan: ConstraintLayout = v.findViewById(R.id.cl_scan)

                val avatarView: GroupAvatarView = v.findViewById(R.id.avatarView)
                val tvName: AppCompatTextView = v.findViewById(R.id.tv_name)
                val tvContent: AppCompatTextView = v.findViewById(R.id.tv_content)

                avatarView.setAvatar(groupAvatar?.getAvatarData())
                tvName.text = groupName

                tvContent.text = context.getString(R.string.invite_group_code, PackageUtil.getAppName())

                clScan.setOnClickListener {
                    ScanActivity.startActivity(context)
                }

                v.findViewById<AppCompatImageView>(R.id.iv_close).setOnClickListener {
                    mGroupInviteDialog?.dismiss()
                }
            }
        })
            .setBackgroundColor(ContextCompat.getColor(context, com.difft.android.base.R.color.bg2))
            .show()

        getGroupInviteCode(context, inviteCode, myName, groupName, ivQR, clShare, clCopy)
    }

    private fun getGroupInviteCode(context: Activity, inviteCode: String, myName: String, groupName: String, imageView: AppCompatImageView?, clShare: ConstraintLayout?, clCopy: ConstraintLayout?) {
        val url = "${urlManager.inviteGroupUrl.trimEnd('/')}/u/g.html?i=$inviteCode"
        val content = context.getString(R.string.invite_group_tips, myName, groupName, url)

        imageView?.setImageBitmap(createQRBitmap(context, url))

        clShare?.setOnClickListener {
            context.shareText(content)
        }

        clCopy?.setOnClickListener {
            Util.copyToClipboard(context, content)
            mInviteDialog?.dismiss()
        }
    }


    fun getGroupInfoByInviteCode(activity: Activity, inviteCode: String) {
        groupRepo.getGroupInfoByInviteCode(inviteCode)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(activity as LifecycleOwner))
            .subscribe({
                if (it.status == 0) {
                    it.data?.let { it1 -> showJoinGroupDialog(activity, it1, inviteCode) }
                } else {
                    PopTip.show(it.reason)
                }
            }, {
                it.printStackTrace()
                L.e { "getGroupInfoByInviteCode error: ${it.stackTraceToString()}" }
                PopTip.show(activity.getString(R.string.chat_net_error))
            })
    }

    private var mJoinGroupDialog: FullScreenDialog? = null

    private fun showJoinGroupDialog(activity: Activity, data: GroupInfoByInviteCodeResp, inviteCode: String) {
        mJoinGroupDialog = FullScreenDialog.build(object : OnBindView<FullScreenDialog>(R.layout.layout_join_group) {
            @SuppressLint("SetTextI18n")
            override fun onBind(dialog: FullScreenDialog, v: View) {
                v.findViewById<AppCompatImageView>(R.id.iv_close).setOnClickListener {
                    dialog.dismiss()
                }
                v.findViewById<GroupAvatarView>(R.id.imageview_group).setAvatar(data.avatar?.getAvatarData())

                v.findViewById<AppCompatTextView>(R.id.textview_group_name).text = data.name + "(" + data.membersCount + ")"

                v.findViewById<ChativeButton>(R.id.btn_join).setOnClickListener {
                    mJoinGroupDialog?.dismiss()
                    joinGroupByInviteCode(inviteCode, activity)
                }
            }
        })
            .setBackgroundColor(ContextCompat.getColor(activity, com.difft.android.base.R.color.bg2))
            .show()
    }

    fun joinGroupByInviteCode(inviteCode: String, activity: Activity, needFinish: Boolean = false) {
        groupRepo.joinGroupByInviteCodeResp(inviteCode)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(activity as LifecycleOwner))
            .subscribe({
                if (it.status == 0) {
                    it.data?.let { it1 ->
                        GroupChatContentActivity.startActivity(activity, it1.gid)
                        if (needFinish) {
                            activity.finish()
                        }
                    }
                } else {
                    val message = when (it.status) {
                        10120 -> activity.getString(R.string.invite_invalid_invite_Code)
                        10121 -> activity.getString(R.string.invite_link_invitation_is_disabled)
                        10122 -> activity.getString(R.string.invite_only_moderators)
                        10123 -> activity.getString(R.string.invite_group_has_been_disbanded)
                        10124 -> activity.getString(R.string.invite_group_is_invalid)
                        else -> it.reason
                    }

                    showErrorAndFinish(message ?: "", activity, needFinish)
                }
            }, {
                it.printStackTrace()
                showErrorAndFinish(it.message ?: "", activity, needFinish)
            })
    }


//    private fun getRoundCornerBitmap(bitmap: Bitmap): Bitmap? {
//        val roundPx = 8.dp.toFloat()
//        val output = Bitmap.createBitmap(
//            bitmap.width, bitmap
//                .height, Bitmap.Config.ARGB_8888
//        )
//        val canvas = Canvas(output)
//        val color = -0xd938c3
//        val paint = Paint()
//        val rect = Rect(0, 0, bitmap.width, bitmap.height)
//        val rectF = RectF(rect)
//        paint.isAntiAlias = true
//        canvas.drawARGB(0, 0, 0, 0)
//        paint.color = color
//        canvas.drawRoundRect(rectF, roundPx, roundPx, paint)
//        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
//        canvas.drawBitmap(bitmap, rect, rect, paint)
//        return output
//    }
}