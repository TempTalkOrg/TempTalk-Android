package com.difft.android.call.util

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.messageserialization.db.store.formatBase58Id

object IdUtil {

    fun getUidByIdentity(identity: String?): String? {
        var userId = identity
        if (!identity.isNullOrEmpty() && identity.contains(".")) {
            userId = identity.split(".")[0]
        }
        return userId
    }

    fun convertToBase58UserName(identity: String?): String? {
        val userId = identity?.split(".")?.firstOrNull() ?: return null
        if (!ValidatorUtil.isUid(userId)) {
            L.e { "[Call] LCallManager convertToBase58UserName error identity:$identity" }
            return null
        }
        return userId.formatBase58Id()
    }

    fun isPersonalMobileDevice(identity: String?): Boolean {
        if (identity == null) return false
        return identity.matches(Regex("""^\+[0-9]+\.1${'$'}"""))
    }

    fun getMyIdentity(): String {
        return "${globalServices.myId}.$DEFAULT_DEVICE_ID"
    }
}