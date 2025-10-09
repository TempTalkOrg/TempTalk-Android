package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.SecureSharedPrefsUtil
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
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.util.Util
import javax.inject.Inject

@AndroidEntryPoint
class AccountActivity : BaseActivity() {

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var chatHttpClient: ChativeHttpClient

    @Inject
    lateinit var settingRepo: SettingRepo

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var logoutManager: LogoutManager

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, AccountActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityAccountBinding by viewbind()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.tvId.text = globalServices.myId.formatBase58Id(false)
        mBinding.tvId.setOnLongClickListener {
            Util.copyToClipboard(this, mBinding.tvId.text)
            true
        }

        mBinding.clLogout.setOnClickListener {
            val phone = userManager.getUserData()?.phoneNumber
            val email = userManager.getUserData()?.email
            if (phone.isNullOrEmpty() && email.isNullOrEmpty()) {
                ComposeDialogManager.showMessageDialog(
                    context = this,
                    title = getString(R.string.me_logout_ok),
                    message = getString(R.string.me_logout_no_account_linked_tips),
                    confirmText = getString(R.string.me_logout_continue),
                    cancelText = getString(R.string.me_logout_cancel),
                    confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(this, com.difft.android.base.R.color.t_error)),
                    onConfirm = {
                        DeleteAccountActivity.startActivity(this)
                    }
                )
            } else {
                showLogoutDialog()
            }
        }

        updateEmailAndPhoneUI()
    }

    private fun showLogoutDialog() {
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = getString(R.string.me_logout_ok),
            message = getString(R.string.me_logout_tips),
            confirmText = getString(R.string.me_logout_ok),
            cancelText = getString(R.string.me_logout_cancel),
            onConfirm = {
                performLogout()
            },
            confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(this, com.difft.android.base.R.color.t_error))
        )
    }

    private fun performLogout() {
        lifecycleScope.launch {
            ComposeDialogManager.showWait(this@AccountActivity, "")
            try {
                withContext(Dispatchers.IO) {
                    chatHttpClient.httpService.fetchLogout(SecureSharedPrefsUtil.getBasicAuth()).blockingGet()
                }
                withContext(Dispatchers.Main) {
                    ComposeDialogManager.dismissWait()
                    logoutManager.doLogout()
                }
            } catch (e: Exception) {
                L.e { "request Logout fail:" + e.stackTraceToString() }
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
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    settingRepo.getProfile(SecureSharedPrefsUtil.getToken()).blockingGet()
                }

                withContext(Dispatchers.Main) {
                    if (result.status == 0) {
                        result.data?.let { info ->
                            userManager.update {
                                this.email = info.emailMasked
                                this.phoneNumber = info.phoneMasked
                            }
                            updateEmailAndPhoneUI()
                        }
                    } else {
                        result.reason?.let { message -> ToastUtil.show(message) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    e.printStackTrace()
                    e.message?.let { message -> ToastUtil.show(message) }
                }
            }
        }
    }

    private fun updateEmailAndPhoneUI() {
        val emailMasked = userManager.getUserData()?.email
        val phoneMasked = userManager.getUserData()?.phoneNumber

        // Email UI 更新
        if (!emailMasked.isNullOrEmpty()) {
            mBinding.tvEmail.text = if (emailMasked.contains("*")) {
                emailMasked
            } else {
                maskString(emailMasked)
            }
            mBinding.clEmail.setOnClickListener {
                BindAccountActivity.startActivity(this, BindAccountActivity.TYPE_CHANGE_EMAIL)
            }
        } else {
            mBinding.tvEmail.text = getString(R.string.me_account_not_linked)
            mBinding.clEmail.setOnClickListener {
                BindAccountActivity.startActivity(this, BindAccountActivity.TYPE_BIND_EMAIL)
            }
        }

        // Phone UI 更新
        if (!phoneMasked.isNullOrEmpty()) {
            mBinding.tvPhone.text = if (phoneMasked.contains("*")) {
                phoneMasked
            } else {
                maskString(phoneMasked)
            }
            mBinding.clPhone.setOnClickListener {
                BindAccountActivity.startActivity(this, BindAccountActivity.TYPE_CHANGE_PHONE)
            }
        } else {
            mBinding.tvPhone.text = getString(R.string.me_account_not_linked)
            mBinding.clPhone.setOnClickListener {
                BindAccountActivity.startActivity(this, BindAccountActivity.TYPE_BIND_PHONE)
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
}