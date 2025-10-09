package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.databinding.ActivityDeleteAccountBinding
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
@AndroidEntryPoint
class DeleteAccountActivity : BaseActivity() {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var chatHttpClient: ChativeHttpClient

    @Inject
    lateinit var logoutManager: LogoutManager

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, DeleteAccountActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityDeleteAccountBinding by viewbind()
    val token: String by lazy {
        SecureSharedPrefsUtil.getToken()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        val myIdBase58 = globalServices.myId.formatBase58Id(false)
        val lastSix = if (myIdBase58.length >= 6) myIdBase58.takeLast(6) else myIdBase58
        mBinding.tvTips2.text = getString(R.string.me_delete_account_tips2, lastSix)

        mBinding.etAccount.addTextChangedListener {
            val etContent = mBinding.etAccount.text.toString().trim()
            mBinding.btnDone.isEnabled = lastSix == etContent
        }


        mBinding.btnDone.setOnClickListener {
            ComposeDialogManager.showMessageDialog(
                context = this,
                title = getString(R.string.me_delete_dialog_title),
                message = getString(R.string.me_delete_dialog_content),
                confirmText = getString(R.string.me_delete_dialog_ok),
                cancelText = getString(R.string.me_delete_dialog_cancel),
                onConfirm = {
                    mBinding.btnDone.isLoading = true
                    chatHttpClient.httpService.fetchDeleteAccount(SecureSharedPrefsUtil.getBasicAuth())
                        .compose(RxUtil.getSingleSchedulerComposer())
                        .to(RxUtil.autoDispose(this))
                        .subscribe({
                            mBinding.btnDone.isLoading = false
                            if (it.status == 0) {
                                logoutManager.doLogout()
                            } else {
                                it.reason?.let { message -> ToastUtil.showLong(message) }
                            }
                        }, {
                            mBinding.btnDone.isLoading = false
                            it.message?.let { message -> ToastUtil.showLong(message) }
                            it.printStackTrace()
                        })
                },
                confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(this, com.difft.android.base.R.color.t_error))
            )
        }
    }
}