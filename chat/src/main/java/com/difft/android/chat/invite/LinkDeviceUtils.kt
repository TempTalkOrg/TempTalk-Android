package com.difft.android.chat.invite

import android.app.Activity
import android.text.TextUtils
import android.util.Base64
import androidx.lifecycle.LifecycleOwner

import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.R
import io.reactivex.rxjava3.core.Single
import org.signal.libsignal.protocol.ecc.Curve
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.cryptonew.EncryptionDataManager
import javax.inject.Inject
import javax.inject.Singleton
import com.difft.android.base.widget.ToastUtil
@Singleton
class LinkDeviceUtils @Inject constructor(
    private val encryptionDataManager: EncryptionDataManager
) {

    /**
     * mac扫码登录
     */
    fun linkDevice(activity: Activity, ephemeralId: String?, publicKeyEncoded: String?, needFinish: Boolean = false) {
        if (!TextUtils.isEmpty(ephemeralId) && !TextUtils.isEmpty(publicKeyEncoded)) {
            ComposeDialogManager.showMessageDialog(
                context = activity,
                title = activity.getString(R.string.link_device),
                message = activity.getString(R.string.link_device_tips),
                confirmText = activity.getString(R.string.link_new_device),
                cancelText = activity.getString(R.string.link_device_cancel),
                onConfirm = {
                    Single.fromCallable {
                        val accountManager = ApplicationDependencies.getSignalServiceAccountManager()
                        val verificationCode = accountManager.newDeviceVerificationCode
                        val publicKey = Curve.decodePoint(Base64.decode(publicKeyEncoded!!, Base64.DEFAULT), 0)
                        val aciIdentityKeyPair = encryptionDataManager.getAciIdentityKey()
                        val id = globalServices.myId
                        accountManager.addDevice(
                            ephemeralId,
                            publicKey,
                            aciIdentityKeyPair,
                            verificationCode,
                            id
                        )
                    }
                        .compose(RxUtil.getSingleSchedulerComposer())
                        .to(RxUtil.autoDispose(activity as LifecycleOwner))
                        .subscribe({
                            TextSecurePreferences.setMultiDevice(activity, true)

                            if (needFinish) {
                                activity.finish()
                            }
                        }, { e ->
                            e.printStackTrace()
                            TextSecurePreferences.setMultiDevice(activity, false)

                            if (needFinish) {
                                e.message?.let { ToastUtil.showLong(it) }
                                activity.finish()
                            } else {
                                e.message?.let { ToastUtil.showLong(it) }
                            }
                        })
                },
                onCancel = {
                    if (needFinish) {
                        activity.finish()
                    }
                })
        }
    }

}