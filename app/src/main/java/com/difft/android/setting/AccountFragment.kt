package com.difft.android.setting

import android.os.Bundle
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import com.difft.android.base.utils.pane.dismissWithView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.utils.pane.*
import com.difft.android.setting.mvi.AccountSettingsContract
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.databinding.ActivityAccountBinding
import com.difft.android.login.BindAccountActivity
import com.difft.android.messageserialization.db.store.formatBase58Id
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import com.difft.android.chat.util.Util

/**
 * Fragment for account settings
 * Can be displayed in both Activity (single-pane) and dual-pane mode
 */
@AndroidEntryPoint
class AccountFragment : Fragment(), SettingsIdiomPage {

    companion object {
        fun newInstance() = AccountFragment()
    }

    private val model: com.difft.android.setting.mvi.AccountSettingsViewModel by viewModels()
    private val searchWait = PageWait(this)
    private val logoutWait = PageWait(this)
    private val logoutWaitRequested = MutableStateFlow(false)
    private val userData get() = model.state.value.user

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

        logoutWaitRequested.value = false
        setupBackButton(binding.ibBack)
        applyDetailPageInsets(view)
        val searchKey = "account-search:${globalServices.myId}"
        waitWhileVisibleTop(PageOperations.running(searchKey), searchWait)
        // A search write from a replaced page can finish while this page shows its older snapshot.
        onOperationEnded(searchKey) { model.dispatch(AccountSettingsContract.Intent.Reload) }
        waitWhileVisibleTop(combine(model.state, logoutWaitRequested) { state, requested -> state.busy && requested }, logoutWait)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    if (!state.busy) logoutWait.dismiss()
                    updateUidUI()
                    updateEmailAndPhoneUI()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.effect.collect { effect ->
                    when (effect) {
                        AccountSettingsContract.Effect.Applied -> Unit
                        is AccountSettingsContract.Effect.Failed -> ToastUtil.show(if (effect.accepted) getString(com.difft.android.base.R.string.settings_accepted_local_commit_failed) else effect.message ?: getString(R.string.operation_failed))
                    }
                }
            }
        }

        binding.tvId.setOnLongClickListener {
            Util.copyToClipboard(requireContext(), binding.tvId.text)
            true
        }
        // The long-press listener makes the text consume touches, so a tap on the ID itself must forward to the row.
        binding.tvId.setOnClickListener { binding.clId.performClick() }

        binding.clLogout.setOnClickListener {
            val phone = userData?.phoneNumber
            val email = userData?.email
            if (phone.isNullOrEmpty() && email.isNullOrEmpty()) {
                val pageDialog = dismissWithView(ComposeDialogManager.showMessageDialog(
                    context = requireContext(),
                    title = getString(R.string.me_logout_ok),
                    message = getString(R.string.me_logout_no_account_linked_tips),
                    confirmText = getString(R.string.me_logout_continue),
                    cancelText = getString(R.string.me_logout_cancel),
                    confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_error)),
                    onConfirm = {
                        detailNavigation().push(this, DeleteAccountDestination)
                    }
                ))
                dismissWhenLeavingVisibleTop { pageDialog?.dismiss() }
            } else {
                showLogoutDialog()
            }
        }

        binding.clId.setOnClickListener {
            detailNavigation().push(this, SetCustomIdDestination(getContactId()))
        }

        binding.switchCanIdSearch.setOnToggleRequestListener { _, requested ->
            changeIdSearchSetting(requested)
        }

        updateUidUI()

        updateEmailAndPhoneUI()
    }

    private fun showLogoutDialog() {
        val pageDialog = dismissWithView(ComposeDialogManager.showMessageDialog(
            context = requireContext(),
            title = getString(R.string.me_logout_ok),
            message = getString(R.string.me_logout_tips),
            confirmText = getString(R.string.me_logout_ok),
            cancelText = getString(R.string.me_logout_cancel),
            onConfirm = {
                performLogout()
            },
            confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_error))
        ))
        dismissWhenLeavingVisibleTop { pageDialog?.dismiss() }
    }

    private fun performLogout() {
        if (model.state.value.busy || !detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        logoutWaitRequested.value = true
        val originalWait = logoutWait
        model.dispatch(AccountSettingsContract.Intent.Logout { originalWait.dismiss() })
    }

    override fun onResume() {
        super.onResume()
        model.dispatch(AccountSettingsContract.Intent.Refresh)
    }

    private fun changeIdSearchSetting(enabled: Boolean) {
        model.dispatch(AccountSettingsContract.Intent.SetSearch(enabled))
    }

    private fun updateUidUI() {
        val searchByCustomUid = userData?.searchByCustomUid
        binding.tvId.text = getContactId()
        binding.switchCanIdSearch.isChecked = searchByCustomUid == 1
        binding.switchCanIdSearch.toggleRequestInFlight = model.state.value.busy
    }

    private fun getContactId(): String {
        val myId = globalServices.myId.formatBase58Id(false)
        val customUid = userData?.customUid
        return if (customUid.isNullOrEmpty()) myId else customUid
    }

    private fun updateEmailAndPhoneUI() {
        val emailMasked = userData?.email
        val phoneMasked = userData?.phoneNumber

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

