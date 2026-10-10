package com.difft.android.setting

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.difft.android.setting.data.CheckUpdateResponse

/**
 * A confirmed update offer (server check or insider version file). The server's updateWithApk is
 * deliberately not carried: the channel's delivery decides how to install.
 */
data class UpdateOffer(val url: String, val apkHash: String)

fun CheckUpdateResponse.toOffer() = UpdateOffer(url, apkHash)

/**
 * How this channel turns a confirmed offer into an installed update. Bound per channel flavor:
 * sideload channels download and install in-app, store channels open their store listing.
 * Unscoped on purpose: the sideload binding holds a dialog tied to its host, one per UpdateManager.
 */
interface UpdateDelivery {
    /** True while an earlier delivery is still running; a new confirm is then refused with a toast. */
    val isInProgress: Boolean

    /** [onForceHandOff] dismisses the non-cancelable force prompt when the delivery replaces it. */
    fun deliver(context: Context, offer: UpdateOffer, isForce: Boolean, onForceHandOff: () -> Unit)

    /** Hooks delivery-completion events to [activity]'s lifecycle. No-op for store channels. */
    fun attach(activity: FragmentActivity, onForceHandOff: () -> Unit)
}
