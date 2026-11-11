package com.difft.android.me

import androidx.core.view.updatePadding
import com.difft.android.base.ui.topchrome.TopChromeStyle
import com.difft.android.base.ui.topchrome.TopChromeOwner
import com.difft.android.base.ui.glass.installAppBarOverlayChrome
import com.difft.android.base.ui.sidechrome.padForSideChrome
import com.difft.android.base.ui.sidechrome.sideChromeBar
import android.content.Intent
import com.difft.android.base.log.lumberjack.L
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.difft.android.BuildConfig
import com.difft.android.R
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DualPaneHost
import com.difft.android.base.utils.padBottomForHomeTabBar
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.chat.media.AvatarEncryptedProvider
import com.difft.android.chat.media.AvatarPreview
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.invite.InviteUtils
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.databinding.MeFragmentBinding
import com.difft.android.linkeddevices.LinkedDevicesActivity
import com.difft.android.linkeddevices.LinkedDevicesCountStore
import com.difft.android.linkeddevices.LinkedDevicesFragment
import com.difft.android.login.ContactProfileSettingActivity
import com.difft.android.login.ContactProfileSettingFragment
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.PublicAccountType
import org.difft.app.database.wcdb
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.setting.AboutActivity
import com.difft.android.setting.AboutFragment
import com.difft.android.setting.AccountActivity
import com.difft.android.setting.AccountFragment
import com.difft.android.setting.ChatSettingsActivity
import com.difft.android.setting.ChatSettingsFragment
import com.difft.android.setting.NotificationSettingsActivity
import com.difft.android.setting.NotificationSettingsFragment
import com.difft.android.setting.PrivacySettingActivity
import com.difft.android.setting.PrivacySettingFragment
import com.difft.android.setting.TestActivity
import com.difft.android.setting.language.LanguageActivity
import com.difft.android.setting.language.LanguageFragment
import com.difft.android.setting.theme.ThemeActivity
import com.difft.android.setting.theme.ThemeFragment
import com.difft.android.selector.basic.PictureSelector
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.pictureselector.GlideEngine
import com.difft.android.selector.pictureselector.PictureSelectorUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import javax.inject.Inject

@AndroidEntryPoint
class MeFragment : Fragment(), TopChromeOwner {
    override val topChromeStyle get() = TopChromeStyle.Feather

    private lateinit var binding: MeFragmentBinding

    @Inject
    lateinit var inviteUtils: InviteUtils

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var environmentHelper: EnvironmentHelper

    @Inject
    lateinit var linkedDevicesCountStore: LinkedDevicesCountStore

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = MeFragmentBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        
        viewLifecycleOwner.lifecycleScope.launch {
            linkedDevicesCountStore.refresh()
        }
    }

    




    private fun applyHomeHostChrome() {
        val host = activity as? DualPaneHost
        
        if (host?.isDualPaneMode != true) {
            sideChromeBar(base = true)
            padForSideChrome(binding.root)
        }
        binding.scrollView2.updatePadding(top = resources.getDimensionPixelSize(R.dimen.me_headerless_top_gap))
        
        binding.scrollView2.padBottomForHomeTabBar(viewLifecycleOwner, host)
        installMeGlassChrome(viewLifecycleOwner, binding)
        installAppBarOverlayChrome(
            viewLifecycleOwner, binding.root, binding.homeAppBar, binding.scrollView2, binding.scrollView2,
            covered = (activity as? ConversationNavigationCallback)?.detailCoversList,
            windowLifecycle = requireActivity().lifecycle,
            
            installSource = false,
            
            style = TopChromeStyle.Feather,
            tintRes = com.difft.android.base.R.color.bg,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyHomeHostChrome()
        binding.avatar.setOnClickListener {
            openAvatarPreview()
        }
        binding.clInvite.visibility = View.VISIBLE
        binding.clInvite.setOnClickListener {
            inviteUtils.showInviteDialog(requireActivity())
        }

        binding.clAdvanced.visibility = View.GONE

        binding.llProfile.setOnClickListener {
            navigateToDetailOrActivity(
                ContactProfileSettingFragment.newInstance(ContactProfileSettingFragment.FROM_CONTACT)
            ) {
                ContactProfileSettingActivity.startActivity(
                    requireActivity(),
                    ContactProfileSettingActivity.BUNDLE_VALUE_FROM_CONTACT
                )
            }
        }

        binding.clAccount.setOnClickListener {
            navigateToDetailOrActivity(AccountFragment.newInstance()) {
                AccountActivity.startActivity(requireActivity())
            }
        }

        binding.clLinkedDevices.setOnClickListener {
            navigateToDetailOrActivity(LinkedDevicesFragment.newInstance()) {
                LinkedDevicesActivity.startActivity(requireActivity())
            }
        }

        observeLinkedDevicesCount()

        binding.clPrivacy.setOnClickListener {
            navigateToDetailOrActivity(PrivacySettingFragment.newInstance()) {
                PrivacySettingActivity.startActivity(requireActivity())
            }
        }

        binding.clChat.setOnClickListener {
            navigateToDetailOrActivity(ChatSettingsFragment.newInstance()) {
                ChatSettingsActivity.startActivity(requireActivity())
            }
        }

        binding.clNotification.setOnClickListener {
            navigateToDetailOrActivity(NotificationSettingsFragment.newInstance()) {
                NotificationSettingsActivity.startActivity(requireActivity())
            }
        }

        binding.clHelp.visibility = View.VISIBLE
        binding.clHelp.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                
                
                
                val botId = OfficialAccountCache.state.value.minOrNull()
                    ?: withContext(Dispatchers.IO) {
                        wcdb.contactor.getFirstObject(DBContactorModel.publicAccountType.eq(PublicAccountType.OFFICIAL))?.id
                    }
                if (botId == null) {
                    L.w { "[MeFragment] help click ignored: no official account known yet" }
                    return@launch
                }
                val navigationCallback = activity as? ConversationNavigationCallback
                if (navigationCallback?.isDualPaneMode == true) {
                    navigationCallback.onContactDetailSelected(botId)
                } else {
                    ContactDetailActivity.startActivity(requireContext(), botId)
                }
            }
        }

        binding.clAbout.setOnClickListener {
            navigateToDetailOrActivity(AboutFragment.newInstance()) {
                AboutActivity.startActivity(requireActivity())
            }
        }

        binding.clLanguage.setOnClickListener {
            navigateToDetailOrActivity(LanguageFragment.newInstance()) {
                LanguageActivity.startActivity(requireActivity())
            }
        }

        binding.tvLanguage.text = LanguageUtils.getLanguageName(requireContext())

        binding.clTheme.setOnClickListener {
            navigateToDetailOrActivity(ThemeFragment.newInstance()) {
                ThemeActivity.startActivity(requireActivity())
            }
        }

        
        TextSizeUtil.textSizeState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { textSize -> MeTextScaling.apply(binding, textSize == TextSizeUtil.TEXT_SIZE_LAGER) }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        
        if (environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_DEVELOPMENT) && BuildConfig.DEBUG) {
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
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ContactorUtil.getContactWithID(requireContext(), globalServices.myId)
                }
                if (!isAdded || view == null) return@launch
                if (result.isPresent) {
                    myself = result.get()
                    binding.appCompatTextViewUserName.text = result.get().getDisplayNameForUI()
                    binding.avatar.setAvatar(result.get())
                }
            } catch (e: Exception) {
                L.w { "[MeFragment] error: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun observeContactsUpdate() {
        ContactorUtil.contactsUpdate
            .onEach { updatedIds ->
                if (updatedIds.contains(globalServices.myId)) {
                    initData()
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun observeLinkedDevicesCount() {
        linkedDevicesCountStore.count
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { count ->
                val (visible, text) = linkedDevicesBadgeState(count)
                binding.tvLinkedDevicesCount.visibility = if (visible) View.VISIBLE else View.GONE
                binding.tvLinkedDevicesCount.text = text
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    




    private fun navigateToDetailOrActivity(fragment: Fragment, fallbackAction: () -> Unit) {
        val dualPaneHost = activity as? DualPaneHost
        if (dualPaneHost != null) {
            dualPaneHost.showDetailFragment(fragment)
        } else {
            fallbackAction()
        }
    }

    private fun openAvatarPreview() {
        if (myself == null || TextUtils.isEmpty(myself?.avatar)) return
        val avatarUrl = myself?.avatar?.getContactAvatarData()?.getContactAvatarUrl() ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                AvatarUtil.getCacheFile(avatarUrl)
            } ?: return@launch

            val list = arrayListOf<LocalMedia>().apply {
                this.add(AvatarPreview.localMediaFor(AvatarEncryptedProvider.DIR_AVATAR, file))
            }
            PictureSelector.create(requireActivity())
                .openPreview()
                .isHidePreviewDownload(true)
                .isHidePreviewShare(true)
                .setDefaultLanguage(LanguageConfig.ENGLISH)
                .setLanguage(PictureSelectorUtils.getLanguage(requireContext()))
                .setSelectorUIStyle(PictureSelectorUtils.getSelectorStyle(requireContext()))
                .setImageEngine(GlideEngine.createGlideEngine())
                .startActivityPreview(0, false, list)
        }
    }
}


fun linkedDevicesBadgeState(count: Int?): Pair<Boolean, String> {
    val visible = (count ?: 0) > 0
    return visible to (if (visible) count.toString() else "")
}
