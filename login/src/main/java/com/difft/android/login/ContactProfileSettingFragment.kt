package com.difft.android.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DualPaneUtils.isInDualPaneMode
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.login.databinding.ActivityContactProfileSettingBinding
import com.difft.android.login.viewmodel.ContactProfileSettingViewModel
import com.difft.android.login.viewmodel.LoginViewModel
import com.difft.android.login.viewmodel.LoginViewModel.Companion.DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.network.viewmodel.Status
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.config.SelectModeConfig
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.ImageFileCompressEngine
import com.luck.picture.lib.pictureselector.ImageFileCropEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.wcdb
import util.ScreenLockUtil
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ContactProfileSettingFragment : Fragment() {

    companion object {
        private const val ARG_FROM = "ARG_FROM"

        const val FROM_REGISTER = 1 //已经注册账号的新用户
        const val FROM_CONTACT = 2 //me页面
        const val FROM_SIGN_UP = 3 //未注册的用户

        fun newInstance(from: Int): ContactProfileSettingFragment {
            return ContactProfileSettingFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_FROM, from)
                }
            }
        }
    }

    private var _binding: ActivityContactProfileSettingBinding? = null
    private val binding get() = _binding!!

    private val mViewModel: ContactProfileSettingViewModel by viewModels()
    private val mLoginViewModel: LoginViewModel by viewModels()

    private val from: Int by lazy {
        arguments?.getInt(ARG_FROM, -1) ?: -1
    }

    val token: String by lazy {
        SecureSharedPrefsUtil.getToken()
    }

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var activityProvider: ActivityProvider

    @Inject
    lateinit var logoutManager: LogoutManager

    private val onPicturePermissionForAvatar = registerPermission {
        onPicturePermissionForAvatarResult(it)
    }

    /**
     * ActivityResultLauncher for PictureSelector to avoid callback holding stale Fragment reference.
     * Must use forResult(ActivityResultLauncher) instead of forResult(OnResultCallbackListener).
     */
    private val pictureSelectorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded || view == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedMedia = PictureSelector.obtainSelectorList(result.data)
            if (selectedMedia.isNotEmpty()) {
                val localMedia = selectedMedia[0]
                mAvatarFilePath = localMedia.compressPath ?: localMedia.realPath
                binding.ivAvatar.setAvatar(mAvatarFilePath ?: "")
            }
        }
    }

    var mAvatarFilePath: String? = null
    var mContactor: ContactorModel? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityContactProfileSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        startObserve()
    }

    private fun initView() {
        // Handle back button based on dual-pane mode
        if (isInDualPaneMode()) {
            binding.ibBack.visibility = View.GONE
        } else {
            binding.ibBack.visibility = View.VISIBLE
            binding.ibBack.setOnClickListener { activity?.finish() }
        }

        when (from) {
            FROM_REGISTER -> {
                binding.tvTitle.visibility = View.VISIBLE
                binding.tvSkip.visibility = View.VISIBLE
                binding.ivRefresh.visibility = View.VISIBLE
                binding.btnDone.text = getString(com.difft.android.chat.R.string.contact_profile_next)

                binding.tvSkip.setOnClickListener {
                    gotoIndexActivity()
                }

                binding.ivRefresh.setOnClickListener {
                    generateRandomAvatarAndName()
                }

                generateRandomAvatarAndName()
            }

            FROM_SIGN_UP -> {
                binding.tvTitle.visibility = View.GONE
                binding.tvSkip.visibility = View.GONE
                binding.ivRefresh.visibility = View.VISIBLE
                binding.btnDone.text = getString(R.string.login_start_to_chat)
                binding.ivRefresh.setOnClickListener {
                    generateRandomAvatarAndName()
                }

                generateRandomAvatarAndName()
            }

            FROM_CONTACT -> {
                binding.tvTitle.visibility = View.VISIBLE
                binding.tvSkip.visibility = View.GONE
                binding.ivRefresh.visibility = View.GONE
                binding.btnDone.text = getString(com.difft.android.chat.R.string.contact_profile_done)

                initAvatarAndName()
            }
        }
        binding.etName.addTextChangedListener {
            refreshClearButton()
        }

        binding.btnClear.setOnClickListener {
            binding.etName.text = null
        }
        refreshClearButton()

        binding.btnDone.setOnClickListener {
            if (from == FROM_SIGN_UP) {
                mLoginViewModel.signUpByNonceCode()
            } else {
                mViewModel.setProfile(
                    context = requireActivity(),
                    filePath = mAvatarFilePath,
                    name = binding.etName.text.toString().trim(),
                    contactor = mContactor
                )
            }
        }

        binding.clAvatar.setOnClickListener {
            // check permission
            // callback to select picture in onPicturePermissionForAvatarResult
            onPicturePermissionForAvatar.launchMultiplePermission(PermissionUtil.picturePermissions)
        }
    }

    private fun generateRandomAvatarAndName() {
        // 在 IO 操作之前获取 context，避免在 IO 线程调用 requireContext()
        val context = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (path, name) = withContext(Dispatchers.IO) {
                    deleteCurrentRandomAvatarFile()
                    val avatarPath = AvatarUtil.generateRandomAvatarFile()
                    val randomName = RandomNameUtil.getRandomName(context)
                    avatarPath to randomName
                }
                // 检查 Fragment 状态，避免在 View 销毁后更新 UI
                if (!isAdded || view == null) return@launch
                mAvatarFilePath = path
                if (File(path).exists()) {
                    binding.ivAvatar.setAvatar(path)
                }
                binding.etName.setText(name)
                binding.etName.setSelection(binding.etName.text.toString().trim().length)
            } catch (e: Exception) {
                L.e(e) { "[ContactProfileSettingFragment] generateRandomAvatarAndName error:" }
            }
        }
    }

    private fun deleteCurrentRandomAvatarFile() {
        mAvatarFilePath?.let { path ->
            File(path).takeIf { it.exists() }?.delete()
        }
    }

    private fun createPictureSelector() {
        ScreenLockUtil.temporarilyDisabled = true
        PictureSelector.create(this)
            .openGallery(SelectMimeType.ofImage())
            .setDefaultLanguage(LanguageConfig.ENGLISH)
            .setLanguage(PictureSelectorUtils.getLanguage(requireContext()))
            .setSelectorUIStyle(PictureSelectorUtils.getSelectorStyle(requireContext()))
            .setImageEngine(GlideEngine.createGlideEngine())
            .setSelectionMode(SelectModeConfig.SINGLE)
            .isDirectReturnSingle(true)
            .setCropEngine(ImageFileCropEngine(requireContext(), PictureSelectorUtils.getSelectorStyle(requireContext())))
            .setCompressEngine(ImageFileCompressEngine())
            .forResult(pictureSelectorLauncher)
    }

    private fun refreshClearButton() {
        val etContent = binding.etName.text
        binding.btnDone.isEnabled = !TextUtils.isEmpty(etContent)
        binding.btnClear.animate().apply {
            cancel()
            val toAlpha = if (!TextUtils.isEmpty(etContent)) 1.0f else 0f
            alpha(toAlpha)
        }
    }

    private fun initAvatarAndName() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val contact = withContext(Dispatchers.IO) {
                    wcdb.contactor.getFirstObject(DBContactorModel.id.eq(globalServices.myId))
                }
                // 检查 Fragment 状态，避免在 View 销毁后更新 UI
                if (!isAdded || view == null) return@launch
                contact?.let {
                    mContactor = it
                    binding.etName.setText(it.getDisplayNameForUI())
                    binding.etName.setSelection(binding.etName.text.toString().trim().length)
                    refreshClearButton()
                    binding.ivAvatar.setAvatar(it, 36)
                }
            } catch (e: Exception) {
                L.w(e) { "[ContactProfileSettingFragment] error:" }
            }
        }
    }

    private fun startObserve() {
        mViewModel.observeSetProfile()
        mLoginViewModel.observeSignIn()
    }

    private fun ContactProfileSettingViewModel.observeSetProfile() =
        setProfileResultData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.LOADING -> {
                    binding.btnDone.isLoading = true
                }

                Status.SUCCESS -> {
                    binding.btnDone.isLoading = false
                    if (from == FROM_REGISTER || from == FROM_SIGN_UP) {
                        gotoIndexActivity()
                    }
                    // Only finish activity in single-pane mode (FROM_CONTACT scenario)
                    // In dual-pane mode, the fragment is displayed in the detail pane, no need to finish
                    if (isInDualPaneMode()) {
                        ToastUtil.show(com.difft.android.chat.R.string.operation_successful)
                    } else {
                        activity?.finish()
                    }
                }

                Status.ERROR -> {
                    binding.btnDone.isLoading = false
                    it.exception?.errorMsg?.let { error ->
                        ToastUtil.show(error)
                    }
                    //如果是一键注册，即便是头像信息上传失败，也进入首页
                    if (from == FROM_SIGN_UP) {
                        gotoIndexActivity()
                        activity?.finish()
                    }
                }
            }
        }

    private fun LoginViewModel.observeSignIn() =
        signInLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.LOADING -> binding.btnDone.isLoading = true
                Status.SUCCESS -> {
                    binding.btnDone.isLoading = false
                    mViewModel.setProfile(
                        context = requireActivity(),
                        filePath = mAvatarFilePath,
                        name = binding.etName.text.toString().trim(),
                        null
                    )
                }

                Status.ERROR -> {
                    binding.btnDone.isLoading = false
                    if (it.exception?.errorCode == DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE) {
                        showDifferentAccountLoginDialog()
                    } else {
                        val errorMsg = it.exception?.errorMsg
                        if (!errorMsg.isNullOrBlank()) {
                            ToastUtil.show(errorMsg)
                        } else {
                            ToastUtil.show(com.difft.android.network.R.string.chat_net_error)
                        }
                    }
                }
            }
        }

    /**
     * 显示不同账号登录提示框
     */
    private fun showDifferentAccountLoginDialog() {
        ComposeDialogManager.showMessageDialog(
            context = requireContext(),
            title = getString(R.string.login_different_dialog_title),
            message = getString(R.string.login_different_dialog_content),
            confirmText = getString(R.string.login_different_dialog_ok_text),
            cancelText = getString(R.string.login_different_dialog_cancel_text),
            cancelable = false,
            confirmButtonColor = androidx.compose.ui.graphics.Color(
                ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_error)
            ),
            onConfirm = {
                logoutManager.doLogout()
            },
            onCancel = {
                activity?.finish()
            }
        )
    }

    private fun gotoIndexActivity() {
        // 使用Intent标志清除整个Activity栈，确保登录流程的所有Activity都被关闭
        val intent = Intent(
            requireContext(),
            activityProvider.getActivityClass(ActivityType.INDEX)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        activity?.finish()
    }

    private fun onPicturePermissionForAvatarResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {
                L.d { "onPicturePermissionForAvatarResult: Denied" }
                ToastUtils.showToast(requireContext(), getString(com.difft.android.chat.R.string.not_granted_necessary_permissions))
            }

            PermissionUtil.PermissionState.Granted -> {
                L.d { "onPicturePermissionForAvatarResult: Granted" }
                createPictureSelector()
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {
                L.d { "onPicturePermissionForAvatarResult: PermanentlyDenied" }
                ComposeDialogManager.showMessageDialog(
                    context = requireContext(),
                    title = getString(com.difft.android.chat.R.string.tip),
                    message = getString(com.difft.android.chat.R.string.no_permission_picture_tip),
                    confirmText = getString(com.difft.android.chat.R.string.notification_go_to_settings),
                    cancelText = getString(com.difft.android.chat.R.string.notification_ignore),
                    cancelable = false,
                    onConfirm = {
                        PermissionUtil.launchSettings(requireContext())
                    },
                    onCancel = {
                        ToastUtils.showToast(
                            requireContext(), getString(com.difft.android.chat.R.string.not_granted_necessary_permissions)
                        )
                    }
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        deleteCurrentRandomAvatarFile()
        super.onDestroy()
    }
}

