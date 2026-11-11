package com.difft.android.setting

import android.os.Bundle
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import com.difft.android.base.utils.pane.dismissWithView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.difft.android.R
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.databinding.ActivityPrivacySettingBinding
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.utils.pane.*
import com.difft.android.setting.mvi.AccountSettingsContract
import com.difft.android.base.utils.globalServices
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject





@AndroidEntryPoint
class PrivacySettingFragment : Fragment(), SettingsIdiomPage {

    companion object {
        fun newInstance() = PrivacySettingFragment()
    }

    private var _binding: ActivityPrivacySettingBinding? = null
    private val binding get() = _binding!!

    private val model: com.difft.android.setting.mvi.AccountSettingsViewModel by viewModels()
    private val identityWait = PageWait(this)

    @Inject
    lateinit var proxyConfigProvider: com.difft.android.network.proxy.ProxyConfigProvider

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityPrivacySettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBackButton(binding.ibBack)
        applyDetailPageInsets(view)
        waitWhileVisibleTop(PageOperations.running("identity-rotation:${globalServices.myId}"), identityWait)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect {
                    binding.clRenewIdentityKey.isEnabled = !it.busy
                    updateIdentityTime()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.effect.collect { effect ->
                    when (effect) {
                        AccountSettingsContract.Effect.Applied -> ToastUtil.show(com.difft.android.chat.R.string.operation_successful)
                        is AccountSettingsContract.Effect.Failed -> ToastUtil.show(if (effect.accepted) getString(com.difft.android.base.R.string.settings_accepted_local_commit_failed) else effect.message ?: getString(com.difft.android.chat.R.string.operation_failed))
                    }
                }
            }
        }
        initView()
    }

    private fun initView() {
        binding.clScreenLock.setOnClickListener {
            detailNavigation().push(this, ScreenLockSettingDestination)
        }

        binding.llUseProxy.visibility = View.VISIBLE
        binding.clUseProxy.setOnClickListener {
            detailNavigation().push(this, ProxySettingsDestination)
        }

        binding.clRenewIdentityKey.visibility = View.VISIBLE
        binding.tvRenewKeyTips.visibility = View.VISIBLE
        
        val userData = model.state.value.user
        val lastGenTime = userData?.aciIdentityKeyGenTime ?: 0L
        if (lastGenTime > 0) {
            val timeFormat = SimpleDateFormat(ResUtils.getString(R.string.settings_new_key_time_format), Locale.ENGLISH)
            val date = Date(lastGenTime)
            timeFormat.format(date).let {
                val timeTips = getString(R.string.settings_new_key_time_info, it)
                binding.tvRenewKeyTips.text = getString(R.string.settings_new_key_tips, timeTips)
            }
        } else {
            binding.tvRenewKeyTips.text = getString(R.string.settings_new_key_tips, "")
        }

        binding.clRenewIdentityKey.setOnClickListener {
            val pageDialog = dismissWithView(ComposeDialogManager.showMessageDialog(
                context = requireContext(),
                title = getString(com.difft.android.chat.R.string.me_renew_identity_key),
                message = getString(com.difft.android.chat.R.string.me_renew_identity_key_tips),
                confirmText = getString(com.difft.android.chat.R.string.me_renew_identity_key_generate),
                cancelText = getString(com.difft.android.chat.R.string.me_renew_identity_key_cancle),
                confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_error)),
                onConfirm = {
                    if (detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.dispatch(AccountSettingsContract.Intent.RenewIdentity)
                }
            ))
            dismissWhenLeavingVisibleTop { pageDialog?.dismiss() }
        }

        binding.llDeleteAccount.visibility = View.VISIBLE
        binding.llDeleteAccount.setOnClickListener {
            detailNavigation().push(this, DeleteAccountDestination)
        }
    }

    override fun onResume() {
        super.onResume()
        updateProxyStatus()
    }

    private fun updateProxyStatus() {
        _binding?.tvProxyStatus?.setText(
            if (proxyConfigProvider.isEnabled) R.string.proxy_status_on else R.string.proxy_status_off
        )
    }

    private fun updateIdentityTime() {
        val time = model.state.value.user?.aciIdentityKeyGenTime ?: 0L
        val formatted = if (time > 0L) {
            val format = SimpleDateFormat(getString(R.string.settings_new_key_time_format), Locale.ENGLISH)
            getString(R.string.settings_new_key_time_info, format.format(Date(time)))
        } else ""
        binding.tvRenewKeyTips.text = getString(R.string.settings_new_key_tips, formatted)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

