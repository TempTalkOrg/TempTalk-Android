package com.difft.android.migration.v2.model

import org.difft.app.database.models.MessageModel











fun deriveContentKind(m: MessageModel): ContentKind = when {
    m.type == MessageModel.TYPE_NOTIFY -> ContentKind.SYSTEM
    m.forwardContextDatabaseId.fkOrNull() != null -> ContentKind.FORWARD
    m.type == MessageModel.TYPE_ATTACHMENT -> ContentKind.FILE
    else -> ContentKind.TEXT
}






fun Long?.fkOrNull(): Long? = this?.takeIf { it != 0L }
