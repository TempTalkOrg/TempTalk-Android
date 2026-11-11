package com.difft.android.setting

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.difft.android.setting.data.CheckUpdateResponse





data class UpdateOffer(val url: String, val apkHash: String)

fun CheckUpdateResponse.toOffer() = UpdateOffer(url, apkHash)






interface UpdateDelivery {
    
    val isInProgress: Boolean

    
    fun deliver(context: Context, offer: UpdateOffer, isForce: Boolean, onForceHandOff: () -> Unit)

    
    fun attach(activity: FragmentActivity, onForceHandOff: () -> Unit)
}
