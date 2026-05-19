package com.difft.android.chat.invite

import com.difft.android.base.log.lumberjack.L
import android.text.TextUtils
import android.util.Base64
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope

import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.R
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.signal.libsignal.protocol.ecc.ECPublicKey
import com.difft.android.chat.util.TextSecurePreferences
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.network.signal.DeviceRepository
import javax.inject.Inject
import javax.inject.Singleton
import com.difft.android.base.widget.ToastUtil

@Singleton
class LinkDeviceUtils @Inject constructor(
    private val encryptionDataManager: EncryptionDataManager,
    private val deviceRepository: DeviceRepository,
) {

    /**
     * mac扫码登录
     */
    fun linkDevice(activity: FragmentActivity, ephemeralId: String?, publicKeyEncoded: String?, needFinish: Boolean = false) {
        if (!TextUtils.isEmpty(ephemeralId) && !TextUtils.isEmpty(publicKeyEncoded)) {
            ComposeDialogManager.showMessageDialog(
                context = activity,
                title = activity.getString(R.string.link_device),
                message = activity.getString(R.string.link_device_tips),
                confirmText = activity.getString(R.string.link_new_device),
                cancelText = activity.getString(R.string.link_device_cancel),
                onConfirm = {
                    activity.lifecycleScope.launch {
                        try {
                            // Retrofit suspend funs handle threading; no withContext(IO) needed
                            val verificationCode = deviceRepository.getNewDeviceVerificationCode()
                            val publicKey = ECPublicKey(Base64.decode(publicKeyEncoded!!, Base64.DEFAULT), 0)
                            val aciIdentityKeyPair = encryptionDataManager.getAciIdentityKey()
                            val id = globalServices.myId
                            deviceRepository.addDevice(
                                ephemeralId!!,
                                publicKey,
                                aciIdentityKeyPair,
                                verificationCode,
                                id
                            )
                            TextSecurePreferences.setMultiDevice(activity, true)
                            if (needFinish) {
                                activity.finish()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            L.w { "[LinkDeviceUtils] addDevice error: ${e.stackTraceToString()}" }
                            TextSecurePreferences.setMultiDevice(activity, false)
                            e.message?.let { ToastUtil.showLong(it) }
                            if (needFinish) {
                                activity.finish()
                            }
                        }
                    }
                },
                onCancel = {
                    if (needFinish) {
                        activity.finish()
                    }
                })
        }
    }

}