package com.difft.android.chat.invite

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cn.bingoogolapple.qrcode.zxing.QRCodeEncoder
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.dp
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
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.network.UrlManager
import com.difft.android.network.group.GroupInfoByInviteCodeResp
import com.difft.android.network.group.GroupRepo
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.difft.android.base.widget.ComposeDialogManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.difft.android.base.widget.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.rx3.await
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

    fun showInviteDialog(context: Activity) {
        val fragment = InviteBottomSheetFragment()
        fragment.show((context as FragmentActivity).supportFragmentManager, "InviteDialog")
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
        tvError: AppCompatTextView?,
        onCopyDismiss: (() -> Unit)? = null
    ) {
        if (regenerate == 0 && currentAutoRefreshTimes >= maxAutoRefreshTimes) { //强制刷新不限制次数
            return
        }
        ComposeDialogManager.showWait(context, "")
        inviteRepo.getInviteCode(regenerate, short)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(context as LifecycleOwner))
            .subscribe({
                ComposeDialogManager.dismissWait()
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
                            onCopyDismiss?.invoke()
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
                    it.reason?.let { message -> ToastUtil.show(message) }
                }
            }, {
                ComposeDialogManager.dismissWait()
                L.e { "getInviteCode error: ${it.stackTraceToString()}" }
                ToastUtil.show(context.getString(R.string.chat_net_error))
            })
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
                L.e { "queryByInviteCode error: ${it.stackTraceToString()}" }
                showErrorAndFinish(activity.getString(R.string.chat_net_error), activity, needFinish)
            })
    }

    private fun showErrorAndFinish(tips: String, activity: Activity, needFinish: Boolean = false) {
        ToastUtil.showLong(tips)
        if (needFinish) {
            activity.finish()
        }
    }

    fun showGroupInviteDialog(
        context: Activity,
        myName: String = "",
        groupName: String = "",
        groupAvatar: String? = null,
        inviteCode: String = "",
    ) {
        val fragment = GroupInviteBottomSheetFragment.newInstance(myName, groupName, groupAvatar, inviteCode)
        fragment.show((context as FragmentActivity).supportFragmentManager, "GroupInviteDialog")
    }

    /**
     * 邀请对话框关闭时的清理工作
     */
    fun onInviteDialogDismiss() {
        if (countdownDispose?.isDisposed == false) {
            countdownDispose?.dispose()
        }
        countdownDispose = null
        cancelCodeTextAnimation()
        currentAutoRefreshTimes = 0
    }

    fun getGroupInviteCode(
        context: Activity,
        inviteCode: String,
        myName: String,
        groupName: String,
        imageView: AppCompatImageView?,
        clShare: ConstraintLayout?,
        clCopy: ConstraintLayout?,
        onCopyDismiss: (() -> Unit)? = null
    ) {
        val url = "${urlManager.inviteGroupUrl.trimEnd('/')}/u/g.html?i=$inviteCode"
        val content = context.getString(R.string.invite_group_tips, myName, groupName, url)

        imageView?.setImageBitmap(createQRBitmap(context, url))

        clShare?.setOnClickListener {
            context.shareText(content)
        }

        clCopy?.setOnClickListener {
            Util.copyToClipboard(context, content)
            onCopyDismiss?.invoke()
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
                    it.reason?.let { message -> ToastUtil.show(message) }
                }
            }, {
                L.e { "getGroupInfoByInviteCode error: ${it.stackTraceToString()}" }
                ToastUtil.show(activity.getString(R.string.chat_net_error))
            })
    }

    private fun showJoinGroupDialog(activity: Activity, data: GroupInfoByInviteCodeResp, inviteCode: String) {
        val fragment = JoinGroupBottomSheetFragment.newInstance(data, inviteCode)
        fragment.show((activity as FragmentActivity).supportFragmentManager, "JoinGroupDialog")
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
                L.w { "[InviteUtils] joinGroupByInviteCode error: ${it.stackTraceToString()}" }
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

/**
 * 邀请码底部弹窗Fragment
 */
@AndroidEntryPoint
class InviteBottomSheetFragment() : BaseBottomSheetDialogFragment() {

    @Inject
    lateinit var inviteUtils: InviteUtils

    // 使用默认容器
    override fun getContentLayoutResId(): Int = R.layout.layout_invite

    // 全屏显示
    override fun isFullScreen(): Boolean = true

    // 不显示拖拽条，避免遮挡自定义背景
    override fun showDragHandle(): Boolean = false

    // 禁用默认背景，使用自定义ChatBackgroundDrawable
    override fun useDefaultBackground(): Boolean = false

    override fun onBottomSheetReady(sheet: View, behavior: BottomSheetBehavior<*>) {
        super.onBottomSheetReady(sheet, behavior)
        // 移除sheet默认背景
        sheet.background = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 在根容器上设置自定义渐变背景，并启用圆角裁剪
        view.background = ChatBackgroundDrawable(requireContext(), view, false)
        view.clipToOutline = true
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val radius = 16.dp.toFloat()
                outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
            }
        }
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

        lifecycleScope.launch {
            val myInfo = withContext(Dispatchers.IO) {
                ContactorUtil.getContactWithID(requireContext(), globalServices.myId).await()
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

        view.findViewById<AppCompatImageView>(R.id.iv_close).setOnClickListener {
            dismiss()
        }

        clEnterCode.setOnClickListener {
            dismiss()
            InviteCodeActivity.startActivity(requireActivity())
        }

        // Delay fetching invite code until the dialog is fully displayed to prevent Loading being covered
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

/**
 * 群组邀请底部弹窗Fragment
 */
@AndroidEntryPoint
class GroupInviteBottomSheetFragment() : BaseBottomSheetDialogFragment() {

    @Inject
    lateinit var inviteUtils: InviteUtils

    private val myName: String by lazy { arguments?.getString(ARG_MY_NAME) ?: "" }
    private val groupName: String by lazy { arguments?.getString(ARG_GROUP_NAME) ?: "" }
    private val groupAvatar: String? by lazy { arguments?.getString(ARG_GROUP_AVATAR) }
    private val inviteCode: String by lazy { arguments?.getString(ARG_INVITE_CODE) ?: "" }

    companion object {
        private const val ARG_MY_NAME = "arg_my_name"
        private const val ARG_GROUP_NAME = "arg_group_name"
        private const val ARG_GROUP_AVATAR = "arg_group_avatar"
        private const val ARG_INVITE_CODE = "arg_invite_code"

        fun newInstance(myName: String, groupName: String, groupAvatar: String?, inviteCode: String): GroupInviteBottomSheetFragment {
            return GroupInviteBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MY_NAME, myName)
                    putString(ARG_GROUP_NAME, groupName)
                    putString(ARG_GROUP_AVATAR, groupAvatar)
                    putString(ARG_INVITE_CODE, inviteCode)
                }
            }
        }
    }

    // 使用默认容器（带圆角和拖拽条）
    override fun getContentLayoutResId(): Int = R.layout.layout_group_invite

    // 全屏显示
    override fun isFullScreen(): Boolean = true

    override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {

        var ivQR: AppCompatImageView? = null
        var clShare: ConstraintLayout? = null
        var clCopy: ConstraintLayout? = null

        ivQR = view.findViewById(R.id.iv_QR)
        clShare = view.findViewById(R.id.cl_share)
        clCopy = view.findViewById(R.id.cl_copy)
        val clScan: ConstraintLayout = view.findViewById(R.id.cl_scan)

        val avatarView: GroupAvatarView = view.findViewById(R.id.avatarView)
        val tvName: AppCompatTextView = view.findViewById(R.id.tv_name)
        val tvContent: AppCompatTextView = view.findViewById(R.id.tv_content)

        avatarView.setAvatar(groupAvatar?.getAvatarData())
        tvName.text = groupName

        tvContent.text = requireContext().getString(R.string.invite_group_code, PackageUtil.getAppName())

        clScan.setOnClickListener {
            ScanActivity.startActivity(requireActivity())
        }

        view.findViewById<AppCompatImageView>(R.id.iv_close).setOnClickListener {
            dismiss()
        }

        // 初始化群组邀请码，传入 dismiss 回调
        inviteUtils.getGroupInviteCode(requireActivity(), inviteCode, myName, groupName, ivQR, clShare, clCopy) {
            dismiss()
        }
    }
}

/**
 * 加入群组底部弹窗Fragment
 */
@AndroidEntryPoint
class JoinGroupBottomSheetFragment() : BaseBottomSheetDialogFragment() {

    @Inject
    lateinit var inviteUtils: InviteUtils

    private val data: GroupInfoByInviteCodeResp? by lazy {
        @Suppress("DEPRECATION")
        arguments?.getSerializable(ARG_DATA) as? GroupInfoByInviteCodeResp
    }
    private val inviteCode: String by lazy { arguments?.getString(ARG_INVITE_CODE) ?: "" }

    companion object {
        private const val ARG_DATA = "arg_data"
        private const val ARG_INVITE_CODE = "arg_invite_code"

        fun newInstance(data: GroupInfoByInviteCodeResp, inviteCode: String): JoinGroupBottomSheetFragment {
            return JoinGroupBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_DATA, data)
                    putString(ARG_INVITE_CODE, inviteCode)
                }
            }
        }
    }

    // 使用默认容器（带圆角和拖拽条）
    override fun getContentLayoutResId(): Int = R.layout.layout_join_group

    // 全屏显示
    override fun isFullScreen(): Boolean = true

    @SuppressLint("SetTextI18n")
    override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {

        view.findViewById<AppCompatImageView>(R.id.iv_close).setOnClickListener {
            dismiss()
        }
        view.findViewById<GroupAvatarView>(R.id.imageview_group).setAvatar(data?.avatar?.getAvatarData())

        view.findViewById<AppCompatTextView>(R.id.textview_group_name).text = data?.name + "(" + data?.membersCount + ")"

        view.findViewById<ChativeButton>(R.id.btn_join).setOnClickListener {
            dismiss()
            inviteUtils.joinGroupByInviteCode(inviteCode, requireActivity())
        }
    }
}