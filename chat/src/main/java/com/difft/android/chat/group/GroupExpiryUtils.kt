package com.difft.android.chat.group

import com.difft.android.websocket.api.messages.GroupNotifyDetailType

fun shouldClearGroupExpiryOnNotify(type: Int): Boolean =
    type != GroupNotifyDetailType.UpgradeGroupCrypto.value && type != GroupNotifyDetailType.GroupAccountInvalid.value

fun groupDissolveDaysLeft(expireTime: Long, now: Long): Int {
    if (expireTime <= now) return 0
    val dayMillis = 86_400_000L
    val wholeDays = Math.floorDiv(expireTime, dayMillis) - Math.floorDiv(now, dayMillis)
    val partialDay = if (Math.floorMod(expireTime, dayMillis) > Math.floorMod(now, dayMillis)) 1 else 0
    return (wholeDays + partialDay).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
