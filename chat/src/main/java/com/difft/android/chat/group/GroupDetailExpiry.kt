package com.difft.android.chat.group

import com.difft.android.network.group.GetGroupInfoResp
import org.difft.app.database.models.GroupModel


internal fun GroupModel.applyDetailExpiry(detail: GetGroupInfoResp?) {
    detail?.expireTime?.takeIf { it > 0 }?.let { expireTime = it }
}
