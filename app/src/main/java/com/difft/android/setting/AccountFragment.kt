package com.difft.android.setting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.databinding.ActivityAccountBinding
import com.difft.android.login.BindAccountActivity
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.setting.repo.SettingRepo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.chat.util.Util
import javax.inject.Inject

/**
 * Fragment for account settings
 * Can be displayed in both Activity (single-pane) and dual-pane mode
 */
@AndroidEntryPoint
class AccountFragment : Fragment() {

    companion object {
        fun newInstance() = AccountFragment()
    }

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var chatHttpClient: ChativeHttpClient

    @Inject
    lateinit var settingRepo: SettingRepo

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var logoutManager: LogoutManager

    private var _binding: ActivityAccountBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBackButton(binding.ibBack)

        binding.tvId.setOnLongClickListener {
            Util.copyToClipboard(requireContext(), binding.tvId.text)
            true
        }

        binding.clLogout.setOnClickListener {
            val phone = userManager.getUserData()?.phoneNumber
            val email = userManager.getUserData()?.email
            if (phone.isNullOrEmpty() && email.isNullOrEmpty()) {
                ComposeDialogManager.showMessageDialog(
                    context = requireContext(),
                    title = getString(R.string.me_logout_ok),
                    message = getString(R.string.me_logout_no_account_linked_tips),
                    confirmText = getString(R.string.me_logout_continue),
                    cancelText = getString(R.string.me_logout_cancel),
                    confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_error)),
                    onConfirm = {
                        DeleteAccountActivity.startActivity(requireActivity())
                    }
                )
            } else {
                showLogoutDialog()
            }
        }

        binding.clId.setOnClickListener {
            SetCustomIdActivity.startActivity(requireActivity(), getContactId())
        }

        binding.switchCanIdSearch.setOnToggleRequestListener { _, requested ->
            changeIdSearchSetting(requested)
        }

        updateUidUI()

        updateEmailAndPhoneUI()
    }

    private fun showLogoutDialog() {
        ComposeDialogManager.showMessageDialog(
            context = requireContext(),
            title = getString(R.string.me_logout_ok),
            message = getString(R.string.me_logout_tips),
            confirmText = getString(R.string.me_logout_ok),
            cancelText = getString(R.string.me_logout_cancel),
            onConfirm = {
                performLogout()
            },
            confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_error))
        )
    }

    private fun performLogout() {
        viewLifecycleOwner.lifecycleScope.launch {
            ComposeDialogManager.showWait(requireContext(), "")
            try {
                withContext(Dispatchers.IO) {
                    chatHttpClient.httpService.fetchLogout((userManager.getUserData()?.baseAuth ?: ""))
                }
                withContext(Dispatchers.Main) {
                    ComposeDialogManager.dismissWait()
                    logoutManager.doLogout()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e(e) { "request Logout fail:" }
                withContext(Dispatchers.Main) {
                    ComposeDialogManager.dismissWait()
                    logoutManager.doLogout()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        initEmailAndPhone()
    }

    private fun initEmailAndPhone() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    settingRepo.getProfile((userManager.getUserData()?.microToken ?: ""))
                }

                withContext(Dispatchers.Main) {
                    if (result.status == 0) {
                        result.data?.let { info ->
                            userManager.update {
                                this.email = info.emailMasked
                                this.phoneNumber = info.phoneMasked
                                this.searchByCustomUid = info.searchByCustomUid
                                this.customUid = info.customUid
                            }
                            updateUidUI()
                            updateEmailAndPhoneUI()
                        }
                    } else {
                        result.reason?.let { message -> ToastUtil.show(message) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    L.w { "[AccountFragment] error: ${e.stackTraceToString()}" }
                    e.message?.let { message -> ToastUtil.show(message) }
                }
            }
        }
    }

    /**
     * Controlled toggle: the switch only moves once the server accepted the value, so a failure
     * leaves it where it was and needs no rollback.
     */
    private fun changeIdSearchSetting(enabled: Boolean) {
        val idSwitchRule = if (enabled) 1 else 0
        val token = (userManager.getUserData()?.microToken ?: "")
        ComposeDialogManager.showWait(requireActivity(), "")
        binding.switchCanIdSearch.guardWhile(
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        settingRepo.setProfile(token, searchByCustomUid = idSwitchRule)
                    }
                    if (!isAdded || view == null) return@launch
                    if (result.status != 0) {
                        L.e { "[Settings] change id search setting error, status:${result.status} reason:${result.reason}" }
                        ToastUtil.show(result.reason ?: getString(R.string.operation_failed))
                    } else {
                        // Update userManager right away so a re-entry does not show the old state.
                        userManager.update {
                            this.searchByCustomUid = idSwitchRule
                        }
                        binding.switchCanIdSearch.isChecked = enabled
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isAdded || view == null) return@launch
                    L.e(e) { "[Settings] changeIdSearchSetting error:" }
                    ToastUtil.show(getString(R.string.operation_failed))
                } finally {
                    // Runs on cancellation too, so the dialog never outlives the request.
                    activity?.let { ComposeDialogManager.dismissWait(it) }
                }
            }
        )
    }

    private fun updateUidUI() {
        val searchByCustomUid = userManager.getUserData()?.searchByCustomUid
        binding.tvId.text = getContactId()
        binding.switchCanIdSearch.isChecked = searchByCustomUid == 1
    }

    private fun getContactId(): String {
        val myId = globalServices.myId.formatBase58Id(false)
        val customUid = userManager.getUserData()?.customUid
        return if (customUid.isNullOrEmpty()) myId else customUid
    }

    private fun updateEmailAndPhoneUI() {
        val emailMasked = userManager.getUserData()?.email
        val phoneMasked = userManager.getUserData()?.phoneNumber

        // Email UI 更新
        if (!emailMasked.isNullOrEmpty()) {
            binding.tvEmail.text = if (emailMasked.contains("*")) {
                emailMasked
            } else {
                maskString(emailMasked)
            }
            binding.clEmail.setOnClickListener {
                BindAccountActivity.startActivity(requireActivity(), BindAccountActivity.TYPE_CHANGE_EMAIL)
            }
        } else {
            binding.tvEmail.text = getString(R.string.me_account_not_linked)
            binding.clEmail.setOnClickListener {
                BindAccountActivity.startActivity(requireActivity(), BindAccountActivity.TYPE_BIND_EMAIL)
            }
        }

        // Phone UI 更新
        if (!phoneMasked.isNullOrEmpty()) {
            binding.tvPhone.text = if (phoneMasked.contains("*")) {
                phoneMasked
            } else {
                maskString(phoneMasked)
            }
            binding.clPhone.setOnClickListener {
                BindAccountActivity.startActivity(requireActivity(), BindAccountActivity.TYPE_CHANGE_PHONE)
            }
        } else {
            binding.tvPhone.text = getString(R.string.me_account_not_linked)
            binding.clPhone.setOnClickListener {
                BindAccountActivity.startActivity(requireActivity(), BindAccountActivity.TYPE_BIND_PHONE)
            }
        }
    }

    private fun maskString(input: String): String {
        if (input.length <= 2) return input
        val first = input.first()
        val last = input.last()
        val masked = "*".repeat(input.length - 2)
        return "$first$masked$last"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

