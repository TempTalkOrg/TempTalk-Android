package com.difft.android.chat.group

import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel

data class ChatUIData(
    val contact: ContactorModel?,
    val group: GroupModel?
)
