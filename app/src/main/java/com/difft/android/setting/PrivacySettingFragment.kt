package com.difft.android.setting

import android.os.Bundle
import com.difft.android.base.log.lumberjack.L
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.difft.android.R
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.databinding.ActivityPrivacySettingBinding
import com.difft.android.login.data.RenewIdentityKeyRequestBody
import com.difft.android.login.repo.LoginRepo
import com.difft.android.websocket.internal.util.JsonUtil
import com.difft.android.base.utils.Base64
import dagger.hilt.android.AndroidEntryPoint
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.cryptonew.EncryptionDataManager
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Fragment for privacy settings
 * Can be displayed in both Activity (single-pane) and dual-pane mode
 */
@AndroidEntryPoint
class PrivacySettingFragment : Fragment() {

    companion object {
        fun newInstance() = PrivacySettingFragment()
    }

    private var _binding: ActivityPrivacySettingBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var loginRepo: LoginRepo

    @Inject
    lateinit var encryptionDataManager: EncryptionDataManager

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
        initView()
    }

    private fun initView() {
        binding.clScreenLock.setOnClickListener {
            ScreenLockSettingActivity.startActivity(requireActivity())
        }

        binding.clRenewIdentityKey.visibility = View.VISIBLE
        binding.tvRenewKeyTips.visibility = View.VISIBLE
        // to show the last identity key generate time.
        val userData = userManager.getUserData()
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
            ComposeDialogManager.showMessageDialog(
                context = requireContext(),
                title = getString(com.difft.android.chat.R.string.me_renew_identity_key),
                message = getString(com.difft.android.chat.R.string.me_renew_identity_key_tips),
                confirmText = getString(com.difft.android.chat.R.string.me_renew_identity_key_generate),
                cancelText = getString(com.difft.android.chat.R.string.me_renew_identity_key_cancle),
                confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_error)),
                onConfirm = {
                    renewIdentityKey()
                }
            )
        }

        binding.llDeleteAccount.visibility = View.VISIBLE
        binding.llDeleteAccount.setOnClickListener {
            DeleteAccountActivity.startActivity(requireActivity())
        }
    }

    private fun renewIdentityKey() {
        ComposeDialogManager.showWait(requireContext(), "")
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
            .to(RxUtil.autoDispose(viewLifecycleOwner))
            .subscribe({
                ComposeDialogManager.dismissWait()
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
                            binding.tvRenewKeyTips.text = getString(R.string.settings_new_key_tips, timeTips)
                        }
                    }
                    ToastUtil.show(com.difft.android.chat.R.string.operation_successful)
                } else {
                    it.reason?.let { message -> ToastUtil.show(message) }
                }
            }, {
                L.w { "[PrivacySettingFragment] renewIdentityKey error: ${it.stackTraceToString()}" }
                if (it is HttpException) {
                    when (it.code()) {
                        413 -> {
                            ToastUtil.show(R.string.settings_new_key_times_limit_tips)
                        }

                        else -> {
                            it.message?.let { message -> ToastUtil.show(message) }
                        }
                    }
                } else {
                    it.message?.let { message -> ToastUtil.show(message) }
                }
                ComposeDialogManager.dismissWait()
            })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

