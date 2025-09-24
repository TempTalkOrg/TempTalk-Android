package com.difft.android.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.activity.viewModels
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.login.databinding.ActivityContactProfileSettingBinding
import com.difft.android.login.viewmodel.ContactProfileSettingViewModel
import com.difft.android.login.viewmodel.LoginViewModel
import com.difft.android.network.viewmodel.Status
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.ImageFileCompressEngine
import com.luck.picture.lib.pictureselector.ImageFileCropEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import com.hi.dhl.binding.viewbind
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.PopTip
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.config.SelectModeConfig
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnResultCallbackListener
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.wcdb
import util.ScreenLockUtil
import java.io.File
import java.util.Optional
import javax.inject.Inject

@AndroidEntryPoint
class ContactProfileSettingActivity : BaseActivity() {

    companion object {
        private const val BUNDLE_KEY_FROM = "BUNDLE_KEY_FROM"

        const val BUNDLE_VALUE_FROM_REGISTER = 1 //已经注册账号的新用户
        const val BUNDLE_VALUE_FROM_CONTACT = 2 //me页面
        const val BUNDLE_VALUE_FROM_SIGN_UP = 3 //未注册的用户

        fun startActivity(activity: Activity, from: Int) {
            val intent = Intent(activity, ContactProfileSettingActivity::class.java)
            intent.from = from
            activity.startActivity(intent)
        }

        private var Intent.from: Int
            get() = getIntExtra(BUNDLE_KEY_FROM, -1)
            set(value) {
                putExtra(BUNDLE_KEY_FROM, value)
            }
    }

    private val mBinding: ActivityContactProfileSettingBinding by viewbind()
    private val mViewModel: ContactProfileSettingViewModel by viewModels()
    private val mLoginViewModel: LoginViewModel by viewModels()

    val token: String by lazy {
        SecureSharedPrefsUtil.getToken()
    }

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var activityProvider: ActivityProvider

    private val onPicturePermissionForAvatar = registerPermission {
        onPicturePermissionForAvatarResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
        startObserve()
    }

    var mAvatarFilePath: String? = null
    var mContactor: ContactorModel? = null

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        when (intent.from) {
            BUNDLE_VALUE_FROM_REGISTER -> {
                mBinding.tvTitle.visibility = View.VISIBLE
                mBinding.tvSkip.visibility = View.VISIBLE
                mBinding.ivRefresh.visibility = View.VISIBLE
                mBinding.btnDone.text = getString(com.difft.android.chat.R.string.contact_profile_next)

                mBinding.tvSkip.setOnClickListener {
                    gotoIndexActivity()
                }

                mBinding.ivRefresh.setOnClickListener {
                    generateRandomAvatarAndName()
                }

                generateRandomAvatarAndName()
            }

            BUNDLE_VALUE_FROM_SIGN_UP -> {
                mBinding.tvTitle.visibility = View.GONE
                mBinding.tvSkip.visibility = View.GONE
                mBinding.ivRefresh.visibility = View.VISIBLE
                mBinding.btnDone.text = getString(R.string.login_start_to_chat)
                mBinding.ivRefresh.setOnClickListener {
                    generateRandomAvatarAndName()
                }

                generateRandomAvatarAndName()
            }

            BUNDLE_VALUE_FROM_CONTACT -> {
                mBinding.tvTitle.visibility = View.VISIBLE
                mBinding.tvSkip.visibility = View.GONE
                mBinding.ivRefresh.visibility = View.GONE
                mBinding.btnDone.text = getString(com.difft.android.chat.R.string.contact_profile_done)

                initAvatarAndName()
            }
        }
        mBinding.etName.addTextChangedListener {
            refreshClearButton()
        }

        mBinding.btnClear.setOnClickListener {
            mBinding.etName.text = null
        }
        refreshClearButton()

        mBinding.btnDone.setOnClickListener {
            if (intent.from == BUNDLE_VALUE_FROM_SIGN_UP) {
                mLoginViewModel.signUpByNonceCode()
            } else {
                mViewModel.setProfile(
                    context = this,
                    filePath = mAvatarFilePath,
                    name = mBinding.etName.text.toString().trim(),
                    contactor = mContactor
                )
            }
        }

        mBinding.clAvatar.setOnClickListener {
            // check permission
            // callback to select picture in onPicturePermissionForAvatarResult
            onPicturePermissionForAvatar.launchMultiplePermission(PermissionUtil.picturePermissions)
        }
    }

    private fun generateRandomAvatarAndName() {
        Observable.fromCallable {
            deleteCurrentRandomAvatarFile()
            val path = AvatarUtil.generateRandomAvatarFile()
            val name = RandomNameUtil.getRandomName(this@ContactProfileSettingActivity)
            path to name
        }.compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                val path = it.first
                mAvatarFilePath = path
                if (File(path).exists()) {
                    mBinding.ivAvatar.setAvatar(path)
                }
                mBinding.etName.setText(it.second)
                mBinding.etName.setSelection(mBinding.etName.text.toString().trim().length)
            }, {
                it.printStackTrace()
                L.e { "[ContactProfileSettingActivity] generateRandomAvatarAndName error:" + it.stackTraceToString() }
            })
    }

    private fun deleteCurrentRandomAvatarFile() {
        mAvatarFilePath?.let { path ->
            File(path).takeIf { it.exists() }?.delete()
        }
    }

    private fun createPictureSelector() {
        ScreenLockUtil.pictureSelectorIsShowing = true
        PictureSelector.create(this)
            .openGallery(SelectMimeType.ofImage())
            .setDefaultLanguage(LanguageConfig.ENGLISH)
            .setLanguage(PictureSelectorUtils.getLanguage(this))
            .setSelectorUIStyle(PictureSelectorUtils.getSelectorStyle(this))
            .setImageEngine(GlideEngine.createGlideEngine())
            .setSelectionMode(SelectModeConfig.SINGLE)
            .isDirectReturnSingle(true)
            .setCropEngine(ImageFileCropEngine(this, PictureSelectorUtils.getSelectorStyle(this)))
            .setCompressEngine(ImageFileCompressEngine())
            .forResult(object : OnResultCallbackListener<LocalMedia> {
                override fun onResult(result: ArrayList<LocalMedia>) {
                    ScreenLockUtil.pictureSelectorIsShowing = false
                    if (result.isNotEmpty()) {
                        val localMedia = result[0]
                        mAvatarFilePath = localMedia.compressPath ?: localMedia.realPath
                        mBinding.ivAvatar.setAvatar(mAvatarFilePath ?: "")
                    }
                }

                override fun onCancel() {
                    ScreenLockUtil.pictureSelectorIsShowing = false
                }
            })
    }

    private fun refreshClearButton() {
        val etContent = mBinding.etName.text
        mBinding.btnDone.isEnabled = !TextUtils.isEmpty(etContent)
        mBinding.btnClear.animate().apply {
            cancel()
            val toAlpha = if (!TextUtils.isEmpty(etContent)) 1.0f else 0f
            alpha(toAlpha)
        }
    }

    private fun initAvatarAndName() {
        Single.fromCallable {
            Optional.ofNullable(wcdb.contactor.getFirstObject(DBContactorModel.id.eq(globalServices.myId)))
        }
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.isPresent) {
                    val contact = it.get()
                    mContactor = contact

                    mBinding.etName.setText(contact.getDisplayNameForUI())
                    mBinding.etName.setSelection(mBinding.etName.text.toString().trim().length)
                    refreshClearButton()
                    mBinding.ivAvatar.setAvatar(contact, 36)
                }
            }, { error ->
                error.printStackTrace()
            })
    }

    private fun startObserve() {
        mViewModel.observeSetProfile()
        mLoginViewModel.observeSignIn()
    }

    private fun ContactProfileSettingViewModel.observeSetProfile() =
        setProfileResultData.observe(this@ContactProfileSettingActivity as LifecycleOwner) {
            when (it.status) {
                Status.LOADING -> {
                    mBinding.btnDone.isLoading = true
                }

                Status.SUCCESS -> {
                    mBinding.btnDone.isLoading = false
                    if (intent.from == BUNDLE_VALUE_FROM_REGISTER || intent.from == BUNDLE_VALUE_FROM_SIGN_UP) {
                        gotoIndexActivity()
                    }
                    finish()
                }

                Status.ERROR -> {
                    mBinding.btnDone.isLoading = false
                    it.exception?.errorMsg?.let { error ->
                        PopTip.show(error)
                    }
                    //如果是一键注册，即便是头像信息上传失败，也进入首页
                    if (intent.from == BUNDLE_VALUE_FROM_SIGN_UP) {
                        gotoIndexActivity()
                        finish()
                    }
                }
            }
        }

    private fun LoginViewModel.observeSignIn() =
        signInLiveData.observe(this@ContactProfileSettingActivity as LifecycleOwner) {
            when (it.status) {
                Status.LOADING -> mBinding.btnDone.isLoading = true
                Status.SUCCESS -> {
                    mBinding.btnDone.isLoading = false
                    mViewModel.setProfile(
                        context = this@ContactProfileSettingActivity,
                        filePath = mAvatarFilePath,
                        name = mBinding.etName.text.toString().trim(),
                        null
                    )
                }

                Status.ERROR -> {
                    mBinding.btnDone.isLoading = false
                }
            }
        }

    private fun gotoIndexActivity() {
        // 使用Intent标志清除整个Activity栈，确保登录流程的所有Activity都被关闭
        val intent = Intent(
            this@ContactProfileSettingActivity,
            activityProvider.getActivityClass(ActivityType.INDEX)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    private fun onPicturePermissionForAvatarResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {
                L.d { "onPicturePermissionForAvatarResult: Denied" }
                ToastUtils.showToast(this, getString(com.difft.android.chat.R.string.not_granted_necessary_permissions))
            }

            PermissionUtil.PermissionState.Granted -> {
                L.d { "onPicturePermissionForAvatarResult: Granted" }
                createPictureSelector()
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {
                L.d { "onPicturePermissionForAvatarResult: PermanentlyDenied" }
                MessageDialog.show(
                    getString(com.difft.android.chat.R.string.tip),
                    getString(com.difft.android.chat.R.string.no_permission_picture_tip),
                    getString(com.difft.android.chat.R.string.notification_go_to_settings),
                    getString(com.difft.android.chat.R.string.notification_ignore)
                )
                    .setCancelable(false)
                    .setOkButton { _, _ ->
                        PermissionUtil.launchSettings(this)
                        false
                    }.setCancelButton { _, _ ->
                        ToastUtils.showToast(
                            this, getString(com.difft.android.chat.R.string.not_granted_necessary_permissions)
                        )
                        false
                    }
            }
        }
    }

    override fun onDestroy() {
        deleteCurrentRandomAvatarFile()
        super.onDestroy()
    }
}