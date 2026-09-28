package com.difft.android.migration.v2.handoff

import android.app.KeyguardManager
import android.content.Context
import com.difft.android.base.user.UserData

/**
 * Which user-presence check the old app runs before handing its login session to the new package.
 *
 * Handing the session over is irreversible, so it must not be possible silently on an unlocked phone.
 * This is a presence check, not a crypto-bound unlock — the credential's confidentiality comes from
 * the container's own encryption, so no `CryptoObject` and no `androidx.biometric` dependency.
 * The app's own App Lock wins when configured, otherwise the system device credential stands in; a
 * device with no lock screen has nothing to verify against and is skipped.
 */
object HandoffAuthPolicy {

    enum class Method {
        /** The app's own passcode / pattern is configured — verify with it. */
        APP_LOCK,

        /** No App Lock, but the device has a secure lock screen — confirm the device credential. */
        DEVICE_CREDENTIAL,

        /** Nothing to verify against; proceed straight to the confirmation page. */
        NONE,
    }

    fun required(context: Context, userData: UserData?): Method = when {
        !userData?.passcode.isNullOrEmpty() || !userData?.pattern.isNullOrEmpty() -> Method.APP_LOCK
        isDeviceSecure(context) -> Method.DEVICE_CREDENTIAL
        else -> Method.NONE
    }

    private fun isDeviceSecure(context: Context): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true }
            .getOrDefault(false)
}
