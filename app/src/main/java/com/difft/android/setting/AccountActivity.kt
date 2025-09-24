package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.databinding.ActivityAccountBinding
import com.difft.android.login.BindAccountActivity
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.setting.repo.SettingRepo
import com.hi.dhl.binding.viewbind
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.PopTip
import com.kongzue.dialogx.dialogs.WaitDialog
import com.kongzue.dialogx.util.TextInfo
import dagger.hilt.android.AndroidEntryPoint
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
            if (phone.isNullOrEmpty() && email.isNullOrEmpty()) {
                MessageDialog.show(R.string.me_logout_ok, R.string.me_logout_no_account_linked_tips, R.string.me_logout_continue, R.string.me_logout_cancel)
                    .setOkButton { _, _ ->
                        DeleteAccountActivity.startActivity(this)
                        false
                    }
                    .okTextInfo = TextInfo().apply { fontColor = ContextCompat.getColor(this@AccountActivity, com.difft.android.base.R.color.t_error) }
            } else {
                showLogoutDialog()
            }
        }
    }

    private fun showLogoutDialog() {
        MessageDialog.show(R.string.me_logout_ok, R.string.me_logout_tips, R.string.me_logout_ok, R.string.me_logout_cancel)
            .setOkButton { _, _ ->
                WaitDialog.show(this@AccountActivity, "")
                chatHttpClient.httpService.fetchLogout(SecureSharedPrefsUtil.getBasicAuth())
                    .compose(RxUtil.getSingleSchedulerComposer())
                    .to(RxUtil.autoDispose(this))
                    .subscribe({
                        WaitDialog.dismiss()
                        logoutManager.doLogout()
                    }, {
                        WaitDialog.dismiss()
                        L.e { "request Logout fail:" + it.stackTraceToString() }
                        logoutManager.doLogout()
                    })
                false
            }
            .okTextInfo = TextInfo().apply { fontColor = ContextCompat.getColor(this@AccountActivity, com.difft.android.base.R.color.t_error) }
    }

    override fun onResume() {
        super.onResume()
        initEmailAndPhone()
    }

    private var email: String? = null
    private var phone: String? = null

    private fun initEmailAndPhone() {
        settingRepo.getProfile(SecureSharedPrefsUtil.getToken())
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.status == 0) {
                    it.data?.let { info ->
                        email = info.emailMasked
                        phone = info.phoneMasked
                        if (!info.emailMasked.isNullOrEmpty()) {
                            if (isAllAsterisks(info.emailMasked)) { //兼容旧版本，如果全是*，使用本地缓存的
                                userManager.getUserData()?.email?.let { email ->
                                    mBinding.tvEmail.text = maskString(email)
                                } ?: run {
                                    mBinding.tvEmail.text = info.emailMasked
                                }
                            } else {
                                mBinding.tvEmail.text = info.emailMasked
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
                        if (!info.phoneMasked.isNullOrEmpty()) {
                            if (isAllAsterisks(info.phoneMasked)) { //兼容旧版本，如果全是*，使用本地缓存的
                                userManager.getUserData()?.phoneNumber?.let { phone ->
                                    mBinding.tvPhone.text = maskString(phone)
                                } ?: run {
                                    mBinding.tvPhone.text = info.phoneMasked
                                }
                            } else {
                                mBinding.tvPhone.text = info.phoneMasked
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
                } else {
                    PopTip.show(it.reason)
                }
            }) {
                it.printStackTrace()
                PopTip.show(it.message)
            }
    }

    private fun isAllAsterisks(input: String): Boolean {
        return input.matches(Regex("\\*+"))
    }

    private fun maskString(input: String): String {
        if (input.length <= 2) return input
        val first = input.first()
        val last = input.last()
        val masked = "*".repeat(input.length - 2)
        return "$first$masked$last"
    }
}