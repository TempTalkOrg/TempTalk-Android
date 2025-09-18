package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.databinding.ActivityPrivacySettingBinding
import com.difft.android.login.data.RenewIdentityKeyRequestBody
import com.difft.android.login.repo.LoginRepo
import com.hi.dhl.binding.viewbind
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.PopTip
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.kongzue.dialogx.util.TextInfo
import dagger.hilt.android.AndroidEntryPoint
import util.TimeUtils
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import com.difft.android.websocket.internal.util.JsonUtil
import com.difft.android.websocket.util.Base64
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import org.thoughtcrime.securesms.cryptonew.EncryptionDataManager

@AndroidEntryPoint
class PrivacySettingActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, PrivacySettingActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityPrivacySettingBinding by viewbind()

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var loginRepo: LoginRepo

    @Inject
    lateinit var encryptionDataManager: EncryptionDataManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initView()
    }

    override fun onResume() {
        super.onResume()

        checkAndUpdatePasscodeView()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.clRenewIdentityKey.visibility = View.VISIBLE
        mBinding.tvRenewKeyTips.visibility = View.VISIBLE
        // to show the last identity key generate time.
        val userData = userManager.getUserData()
        val lastGenTime = userData?.aciIdentityKeyGenTime ?: 0L
        if (lastGenTime > 0) {
            val timeFormat = SimpleDateFormat(ResUtils.getString(R.string.settings_new_key_time_format), Locale.ENGLISH)
            val date = Date(lastGenTime)
            timeFormat.format(date).let {
                val timeTips = getString(R.string.settings_new_key_time_info, it)
                mBinding.tvRenewKeyTips.text = getString(R.string.settings_new_key_tips, timeTips)
            }
        } else {
            com.difft.android.chat.R.string.me_renew_identity_key_tips
            mBinding.tvRenewKeyTips.text = getString(R.string.settings_new_key_tips, "")
        }

        mBinding.clRenewIdentityKey.setOnClickListener {
            MessageDialog.show(
                com.difft.android.chat.R.string.me_renew_identity_key,
                com.difft.android.chat.R.string.me_renew_identity_key_tips,
                com.difft.android.chat.R.string.me_renew_identity_key_generate,
                com.difft.android.chat.R.string.me_renew_identity_key_cancle
            )
                .setOkButton { _, _ ->
                    renewIdentityKey()
                    false
                }
                .okTextInfo = TextInfo().apply { fontColor = ContextCompat.getColor(this@PrivacySettingActivity, com.difft.android.base.R.color.t_error) }
        }

        mBinding.llDeleteAccount.visibility = View.VISIBLE
        mBinding.llDeleteAccount.setOnClickListener {
            DeleteAccountActivity.startActivity(this)
        }
    }

    private fun checkAndUpdatePasscodeView() {
        mBinding.tvSecurityTips.text = getString(R.string.settings_screen_lock_tips, PackageUtil.getAppName())
        userManager.getUserData()?.let {
            mBinding.cbScreenLock.setOnCheckedChangeListener(null)
            mBinding.cbScreenLock.isChecked = it.passcode.isNullOrEmpty() == false
            mBinding.cbScreenLock.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    SetPasscodeActivity.startActivity(this)
                } else {
                    deletePasscode()
                }
            }

            if (it.passcode.isNullOrEmpty()) {
                mBinding.clTimeout.visibility = View.GONE
            } else {
                mBinding.clTimeout.visibility = View.VISIBLE
                mBinding.tvTimeout.text = if (it.passcodeTimeout == 0) getString(R.string.settings_screen_lock_timeout_instant) else TimeUtils.millis2FitTimeSpan(it.passcodeTimeout.seconds.inWholeMilliseconds, 3, false)
                mBinding.clTimeout.setOnClickListener {
                    SetPasscodeTimeoutActivity.startActivity(this)
                }
            }
        }
    }

    private fun deletePasscode() {
        userManager.update {
            this.passcode = null
            this.passcodeAttempts = 0
        }
        checkAndUpdatePasscodeView()
    }

    private fun renewIdentityKey() {
        WaitDialog.show(this@PrivacySettingActivity, "")
        val registrationId = KeyHelper.generateRegistrationId(false)
        val newIdentityKeyPair: IdentityKeyPair = IdentityKeyUtil.generateIdentityKeyPair()

        val data = "${Base64.encodeBytesWithoutPadding(newIdentityKeyPair.publicKey.serialize())}$registrationId"
        val newSignByteArray = newIdentityKeyPair.privateKey.calculateSignature(data.toByteArray())

        // 从UserManager获取当前身份密钥
        val currentIdentityKeyPair = encryptionDataManager.getAciIdentityKey()
        val oldSignByteArray = currentIdentityKeyPair.privateKey.calculateSignature(newSignByteArray)

        val requestBody = RenewIdentityKeyRequestBody(Base64.encodeBytesWithoutPadding(newIdentityKeyPair.publicKey.serialize()), registrationId, Base64.encodeBytesWithoutPadding(newSignByteArray), Base64.encodeBytesWithoutPadding(oldSignByteArray))
        loginRepo.renewIdentityKey(SecureSharedPrefsUtil.getBasicAuth(), JsonUtil.toJson(requestBody))
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                WaitDialog.dismiss()
                if (it.status == 0) {
                    val currentTimeMillis = System.currentTimeMillis()

                    // 存储旧ACI身份密钥和时间到UserManager
                    encryptionDataManager.setOldAciIdentityKey(currentIdentityKeyPair)
                    // 更新新的ACI身份密钥对到UserManager
                    encryptionDataManager.updateAciIdentityKey(newIdentityKeyPair)
                    userManager.update {
                        this.aciIdentityKeyGenTime = currentTimeMillis
                    }

                    // 更新UI显示新的身份密钥创建时间
                    ResUtils.getString(R.string.settings_new_key_time_format).let {
                        val timeFormat = SimpleDateFormat(it)
                        val date = Date(currentTimeMillis)
                        timeFormat.format(date).let {
                            val timeTips = getString(R.string.settings_new_key_time_info, it)
                            mBinding.tvRenewKeyTips.text = getString(R.string.settings_new_key_tips, timeTips)
                        }
                    }
                    TipDialog.build()
                        .setMessageContent(com.difft.android.chat.R.string.operation_successful)
                        .setTipType(WaitDialog.TYPE.SUCCESS)
                        .setCancelable(true)
                        .show()
                } else {
                    PopTip.show(it.reason)
                }
            }, {
                it.printStackTrace()
                if (it is HttpException) {
                    when (it.code()) {
                        413 -> {
                            TipDialog.build()
                                .setMessageContent(R.string.settings_new_key_times_limit_tips)
                                .setTipType(WaitDialog.TYPE.WARNING)
                                .setCancelable(true)
                                .show()
                        }

                        else -> {
                            PopTip.show(it.message)
                        }
                    }
                } else {
                    PopTip.show(it.message)
                }
                WaitDialog.dismiss()
            })

    }

}