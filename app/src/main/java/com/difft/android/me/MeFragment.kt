package com.difft.android.me

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.difft.android.R
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.invite.InviteUtils
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.databinding.MeFragmentBinding
import com.difft.android.login.ContactProfileSettingActivity
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.setting.AboutActivity
import com.difft.android.setting.AccountActivity
import com.difft.android.setting.ChatSettingsActivity
import com.difft.android.setting.NotificationSettingsActivity
import com.difft.android.setting.PrivacySettingActivity
import com.difft.android.setting.TestActivity
import com.difft.android.setting.language.LanguageActivity
import com.difft.android.setting.theme.ThemeActivity
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import com.kongzue.dialogx.dialogs.BottomMenu
import com.kongzue.dialogx.interfaces.OnMenuItemSelectListener
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnExternalPreviewEventListener
import com.luck.picture.lib.language.LanguageConfig
import dagger.hilt.android.AndroidEntryPoint
import org.difft.app.database.models.ContactorModel
import javax.inject.Inject

@AndroidEntryPoint
class MeFragment : Fragment() {
    private lateinit var binding: MeFragmentBinding

    @Inject
    lateinit var inviteUtils: InviteUtils

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var environmentHelper: EnvironmentHelper

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = MeFragmentBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        initThemeView()
    }

    private fun initThemeView() {
        val theme = userManager.getUserData()?.theme
        binding.tvTheme.text = when (theme) {
            AppCompatDelegate.MODE_NIGHT_YES -> {
                getString(com.difft.android.chat.R.string.me_theme_dark)
            }

            AppCompatDelegate.MODE_NIGHT_NO -> {
                getString(com.difft.android.chat.R.string.me_theme_light)
            }

            else -> {
                getString(com.difft.android.chat.R.string.me_theme_system)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.avatar.setOnClickListener {
            openAvatarPreview()
        }
        binding.clInvite.visibility = View.VISIBLE
        binding.clInvite.setOnClickListener {
            inviteUtils.showInviteDialog(requireActivity())
        }

        binding.clAdvanced.visibility = View.GONE

        binding.llProfile.setOnClickListener {
            ContactProfileSettingActivity.startActivity(
                requireActivity(),
                ContactProfileSettingActivity.BUNDLE_VALUE_FROM_CONTACT
            )
        }

        binding.clAccount.setOnClickListener {
            AccountActivity.startActivity(requireActivity())
        }

        binding.clPrivacy.setOnClickListener {
            PrivacySettingActivity.startActivity(requireActivity())
        }

        binding.clChat.setOnClickListener {
            ChatSettingsActivity.startActivity(requireActivity())
        }

        binding.clNotification.setOnClickListener {
            NotificationSettingsActivity.startActivity(requireActivity())
        }

        binding.clHelp.visibility = View.VISIBLE
        binding.clHelp.setOnClickListener {
            ChatActivity.startActivity(requireActivity(), getString(com.difft.android.chat.R.string.official_bot_id))
        }

        binding.clAbout.setOnClickListener {
            AboutActivity.startActivity(requireActivity())
        }

        binding.clLanguage.setOnClickListener {
            LanguageActivity.startActivity(requireActivity())
        }

        binding.tvLanguage.text = LanguageUtils.getLanguageName(requireContext())

        binding.clTheme.setOnClickListener {
            ThemeActivity.startActivity(requireActivity())
        }

        binding.clTextSize.setOnClickListener {
            showTextSizeDialog()
        }
        updateTextSizeSettings()

        //测试环境显示测试页面入口
        if (environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_DEVELOPMENT)) {
            binding.clTest.visibility = View.VISIBLE
            binding.clTest.setOnClickListener {
                startActivity(Intent(requireContext(), TestActivity::class.java))
            }
        }
        observeContactsUpdate()

        initData()
    }

    private var myself: ContactorModel? = null
    private fun initData() {
        ContactorUtil.getContactWithID(requireContext(), globalServices.myId)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.isPresent) {
                    myself = it.get()
                    binding.appCompatTextViewUserName.text = it.get().getDisplayNameForUI()
                    binding.avatar.setAvatar(it.get())
                }
            }, { it.printStackTrace() })
    }

    private fun observeContactsUpdate() {
        ContactorUtil.contactsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.contains(globalServices.myId)) {
                    initData()
                }
            }, { it.printStackTrace() })
    }

    private fun showTextSizeDialog() {
        val textSizeList = arrayOf(getString(R.string.me_text_size_default), getString(R.string.me_text_size_lage))
        var selectMenuIndex = userManager.getUserData()?.textSize ?: 0
        BottomMenu.show(textSizeList)
            .setBackgroundColor(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg3))
            .setOnMenuItemClickListener(object : OnMenuItemSelectListener<BottomMenu?>() {
                override fun onOneItemSelect(dialog: BottomMenu?, text: CharSequence, index: Int, select: Boolean) {
                    dialog?.dismiss()
                    selectMenuIndex = index
                    TextSizeUtil.updateTextSize(selectMenuIndex)
                    updateTextSizeSettings()
                }
            })
            .setSelection(selectMenuIndex)
    }

    private fun refreshTextSize() {
        val textViews = findAllTextViews(binding.root)
        textViews.forEach { textView ->
            if (TextSizeUtil.isLager()) {
                textView.textSize = 24f
            } else {
                textView.textSize = 16f
            }
        }
        binding.tvProfile.textSize = if (TextSizeUtil.isLager()) 21f else 14f
    }

    private fun findAllTextViews(root: View): List<TextView> {
        val textViews = mutableListOf<TextView>()
        val stack = mutableListOf<View>()
        stack.add(root)

        var viewCount = 0
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.size - 1)
            viewCount++

            if (current is TextView) {
                textViews.add(current)
            } else if (current is ViewGroup) {
                for (i in current.childCount - 1 downTo 0) {
                    stack.add(current.getChildAt(i))
                }
            }
        }
        return textViews
    }

    private fun updateTextSizeSettings() {
        binding.tvTextSize.text = if (TextSizeUtil.isLager()) getString(R.string.me_text_size_lage) else getString(R.string.me_text_size_default)
        refreshTextSize()
    }


    private fun openAvatarPreview() {
        if (myself == null || TextUtils.isEmpty(myself?.avatar)) return
        val file = AvatarUtil.getFileFormUrl(myself?.avatar?.getContactAvatarData()?.getContactAvatarUrl() ?: "", AvatarUtil.AvatarCacheSize.SMALL)
        if (!file.exists()) return
        val list = arrayListOf<LocalMedia>().apply {
            this.add(LocalMedia.generateLocalMedia(requireActivity(), file.path))
        }
        PictureSelector.create(requireActivity())
            .openPreview()
            .isHidePreviewDownload(false)
            .isAutoVideoPlay(true)
            .isVideoPauseResumePlay(true)
            .setDefaultLanguage(LanguageConfig.ENGLISH)
            .setLanguage(PictureSelectorUtils.getLanguage(requireContext()))
            .setSelectorUIStyle(PictureSelectorUtils.getSelectorStyle(requireContext()))
            .setImageEngine(GlideEngine.createGlideEngine())
            .setExternalPreviewEventListener(object : OnExternalPreviewEventListener {
                override fun onPreviewDelete(position: Int) {
                }

                override fun onLongPressDownload(context: Context?, media: LocalMedia?): Boolean {
                    return false
                }

            }).startActivityPreview(0, false, list)
    }
}