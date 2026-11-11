package com.difft.android.login

import android.app.Activity
import com.difft.android.base.widget.floatAsTopBarTextButton
import com.difft.android.base.widget.floatAsTopBarButton
import android.content.Intent
import android.os.Bundle
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import com.difft.android.base.utils.pane.dismissWithView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMediaSelectionOrOpen
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.pane.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.login.mvi.ProfileEditContract
import com.difft.android.login.mvi.ProfileEditViewModel
import kotlinx.coroutines.flow.map
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.base.widget.WaitDialogHandle
import com.difft.android.base.widget.waitDialogHandle
import com.difft.android.chat.common.AvatarPickTempCleaner
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.login.databinding.ActivityContactProfileSettingBinding
import com.difft.android.login.viewmodel.ContactProfileSettingViewModel
import com.difft.android.login.viewmodel.LoginViewModel
import com.difft.android.login.viewmodel.LoginViewModel.Companion.DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE
import com.difft.android.network.viewmodel.Status
import com.difft.android.selector.basic.PictureSelector
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.config.SelectModeConfig
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.pictureselector.GlideEngine
import com.difft.android.selector.pictureselector.ImageFileCompressEngine
import com.difft.android.selector.pictureselector.ImageFileCropEngine
import com.difft.android.selector.pictureselector.PictureSelectorUtils
import com.difft.android.selector.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import util.ScreenLockUtil
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ContactProfileSettingFragment : Fragment(), SettingsIdiomPage {

    companion object {
        private const val ARG_FROM = "ARG_FROM"

        const val FROM_REGISTER = 1
        const val FROM_CONTACT = 2
        const val FROM_SIGN_UP = 3

        fun newInstance(from: Int): ContactProfileSettingFragment {
            return ContactProfileSettingFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_FROM, from)
                }
            }
        }
    }

    private var restoringContactDraft = true
    private var _binding: ActivityContactProfileSettingBinding? = null
    private val binding get() = _binding!!

    




    private lateinit var profileWait: WaitDialogHandle

    private val editViewModel: ProfileEditViewModel by viewModels()

    private val mViewModel: ContactProfileSettingViewModel by viewModels()
    private val mLoginViewModel: LoginViewModel by viewModels()

    private val from: Int by lazy {
        arguments?.getInt(ARG_FROM, -1) ?: -1
    }

    @Inject
    lateinit var activityProvider: ActivityProvider

    @Inject
    lateinit var logoutManager: LogoutManager

    private val onPicturePermissionForAvatar = registerPermission {
        onPicturePermissionForAvatarResult(it)
    }

    



    private val pictureSelectorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (from == FROM_CONTACT) editViewModel.dispatch(ProfileEditContract.Intent.SetPickerPending(false))
        
        ScreenLockUtil.temporarilyDisabled = false
                if (result.resultCode == Activity.RESULT_OK) {
            val selectedMedia = PictureSelector.obtainSelectorList(result.data)
            if (selectedMedia.isNotEmpty()) {
                val localMedia = selectedMedia[0]
                val selectedPath = localMedia.compressPath ?: localMedia.realPath
                if (from == FROM_CONTACT) editViewModel.dispatch(ProfileEditContract.Intent.SelectAvatar(selectedPath))
                else mAvatarFilePath = selectedPath
                
                
                AvatarPickTempCleaner.deleteCropTemp(localMedia, keepPath = selectedPath)
                _binding?.ivAvatar?.setAvatar(selectedPath.orEmpty())
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
        restoringContactDraft = true
        _binding = ActivityContactProfileSettingBinding.inflate(inflater, container, false)
        
        if (isInDetailStack()) binding.etName.autoFocus = false
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        profileWait = waitDialogHandle()
        
        
        binding.etName.isSaveFromParentEnabled = false
        applyDetailPageInsets(view, ime = true)
        detailKeyboard(showOnFirstOpen = false, focus = {}, savedInstanceState = savedInstanceState)
        if (from != FROM_CONTACT) {
            mAvatarFilePath = savedInstanceState?.getString("profile-avatar")?.takeIf { File(it).canRead() }
            savedInstanceState?.getString("profile-name")?.let { showName(it) }
            mAvatarFilePath?.let { binding.ivAvatar.setAvatar(it) }
        }
        initView()
        startObserve()
    }

    private fun initView() {
        setupBackButton(binding.ibBack)
        binding.ivRefresh.floatAsTopBarButton(leading = false)
        binding.tvSkip.floatAsTopBarTextButton(leading = false)

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

                if (mAvatarFilePath == null) generateRandomAvatarAndName()
            }

            FROM_SIGN_UP -> {
                binding.tvTitle.visibility = View.GONE
                binding.tvSkip.visibility = View.GONE
                binding.ivRefresh.visibility = View.VISIBLE
                binding.btnDone.text = getString(R.string.login_start_to_chat)
                binding.ivRefresh.setOnClickListener {
                    generateRandomAvatarAndName()
                }

                if (mAvatarFilePath == null) generateRandomAvatarAndName()
            }

            FROM_CONTACT -> {
                binding.tvTitle.visibility = View.VISIBLE
                binding.tvSkip.visibility = View.GONE
                binding.ivRefresh.visibility = View.GONE
                binding.btnDone.text = getString(com.difft.android.chat.R.string.contact_profile_done)

                observeProfileEditor()
            }
        }
        binding.etName.onQueryChanged = ::onNameEdited
        binding.etName.onClear = { onNameEdited("") }
        
        binding.etName.onImeAction = { if (binding.btnDone.isEnabled) binding.btnDone.performClick() }
        refreshDoneButton()

        binding.btnDone.setOnClickListener {
            if (from == FROM_SIGN_UP) {
                mLoginViewModel.signUpByNonceCode()
            } else if (from == FROM_CONTACT) {
                editViewModel.dispatch(ProfileEditContract.Intent.Save)
            } else {
                mViewModel.setProfile(
                    filePath = mAvatarFilePath,
                    name = binding.etName.query.trim(),
                    contactor = mContactor
                )
            }
        }

        binding.clAvatar.setOnClickListener {
            
            onPicturePermissionForAvatar.launchMediaSelectionOrOpen { createPictureSelector() }
        }
    }

    private fun generateRandomAvatarAndName() {
        
        val context = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (path, name) = withContext(Dispatchers.IO) {
                    deleteCurrentRandomAvatarFile()
                    val avatarPath = AvatarUtil.generateRandomAvatarFile()
                    val randomName = RandomNameUtil.getRandomName(context)
                    avatarPath to randomName
                }
                
                if (!isAdded || view == null) return@launch
                mAvatarFilePath = path
                if (File(path).exists()) {
                    binding.ivAvatar.setAvatar(path)
                }
                showName(name)
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
        if (from == FROM_CONTACT) editViewModel.dispatch(ProfileEditContract.Intent.SetPickerPending(true))
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

    private fun onNameEdited(name: String) {
        if (from == FROM_CONTACT && !restoringContactDraft && name != editViewModel.state.value.nameDraft) {
            editViewModel.dispatch(ProfileEditContract.Intent.ChangeName(name))
        }
        refreshDoneButton()
    }

    
    private fun showName(name: String) {
        binding.etName.query = name
        refreshDoneButton()
    }

    private fun refreshDoneButton() {
        binding.btnDone.isEnabled = binding.etName.query.isNotEmpty()
    }

    private fun observeProfileEditor() {
        val wait = PageWait(this)
        waitWhileVisibleTop(editViewModel.state.map { it.busy }, wait)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                editViewModel.state.collect { state ->
                    if (binding.etName.query != state.nameDraft) showName(state.nameDraft)
                    state.avatarPath?.let { binding.ivAvatar.setAvatar(it) }
                        ?: state.contact?.let { binding.ivAvatar.setAvatar(it, 36) }
                    binding.btnDone.isEnabled = !state.busy && !state.completed && state.nameDraft.isNotBlank()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { editViewModel.state.collect {
                    if (it.completed && !isInDetailStack()) detailNavigation().pop(this@ContactProfileSettingFragment)
                } }
                editViewModel.effect.collect { effect ->
                    when (effect) {
                        ProfileEditContract.Effect.Saved -> {
                            wait.dismiss()
                            if (isInDetailStack()) ToastUtil.show(com.difft.android.chat.R.string.operation_successful)

                        }
                        ProfileEditContract.Effect.AcceptedLocalCommitFailed -> ToastUtil.show(com.difft.android.base.R.string.settings_accepted_local_commit_failed)
                        ProfileEditContract.Effect.ReselectAvatar -> ToastUtil.show(R.string.profile_avatar_reselect)
                        is ProfileEditContract.Effect.Failed -> ToastUtil.show(effect.message ?: getString(com.difft.android.chat.R.string.operation_failed))
                    }
                }
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
                    profileWait.show()
                }

                Status.SUCCESS -> {
                    profileWait.dismiss()
                    
                    
                    context?.let { ctx -> AvatarPickTempCleaner.deleteUploadedTemp(ctx, mAvatarFilePath) }
                    if (from == FROM_REGISTER || from == FROM_SIGN_UP) {
                        gotoIndexActivity()
                    }
                    else if (isInDetailStack()) {
                        ToastUtil.show(com.difft.android.chat.R.string.operation_successful)
                    } else {
                        detailNavigation().pop(this@ContactProfileSettingFragment)
                    }
                }

                Status.ERROR -> {
                    profileWait.dismiss()
                    it.exception?.errorMsg?.let { error ->
                        ToastUtil.show(error)
                    }
                    
                    if (from == FROM_SIGN_UP) {
                        gotoIndexActivity()
                        detailNavigation().pop(this@ContactProfileSettingFragment)
                    }
                }
            }
        }

    private fun LoginViewModel.observeSignIn() =
        signInLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.LOADING -> profileWait.show()
                Status.SUCCESS -> {
                    
                    
                    mViewModel.setProfile(
                            filePath = mAvatarFilePath,
                        name = binding.etName.query.trim(),
                        null
                    )
                }

                Status.ERROR -> {
                    profileWait.dismiss()
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

    private fun showDifferentAccountLoginDialog() {
        val pageDialog = dismissWithView(ComposeDialogManager.showMessageDialog(
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
                detailNavigation().pop(this@ContactProfileSettingFragment)
            }
        ))
        dismissWhenLeavingVisibleTop { pageDialog?.dismiss() }
    }

    private fun gotoIndexActivity() {
        
        val intent = Intent(
            requireContext(),
            activityProvider.getActivityClass(ActivityType.INDEX)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        detailNavigation().pop(this@ContactProfileSettingFragment)
    }

    private fun onPicturePermissionForAvatarResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {

                ToastUtils.showToast(requireContext(), getString(com.difft.android.chat.R.string.not_granted_necessary_permissions))
            }

            PermissionUtil.PermissionState.Granted -> {

                createPictureSelector()
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {

                val pageDialog = dismissWithView(ComposeDialogManager.showMessageDialog(
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
                ))
                dismissWhenLeavingVisibleTop { pageDialog?.dismiss() }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        restoringContactDraft = false
        if (from == FROM_CONTACT) showName(editViewModel.state.value.nameDraft)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (from != FROM_CONTACT) {
            outState.putString("profile-avatar", mAvatarFilePath)
            outState.putString("profile-name", _binding?.etName?.query)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        if (from != FROM_CONTACT && activity?.isChangingConfigurations != true && mViewModel.setProfileResultData.value?.status != Status.LOADING) deleteCurrentRandomAvatarFile()
        super.onDestroy()
    }
}

