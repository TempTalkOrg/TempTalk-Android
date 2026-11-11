package com.difft.android.messageserialization.db.store

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.Base58
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.models.ContactorModel
import java.util.Optional

fun <T> Optional<T>.getOrNull(): T? {
    return if (isPresent) {
        get()
    } else {
        null
    }
}





fun ContactorModel.getDisplayNameForUI(): String {
    return getFirstNonEmptyValue(
        ContactRemarkCache.getRemark(this.id),
        remark,
        groupMemberContactor?.remark,
        publicName,
        name,
        groupMemberContactor?.displayName,
        id.formatBase58Id()
    )
}





fun ContactorModel.getDisplayNameWithoutRemarkForUI(): String {
    return getFirstNonEmptyValue(
        publicName,
        name,
        groupMemberContactor?.displayName,
        id.formatBase58Id()
    )
}





fun ContactorModel.getEffectiveAvatarJson(): String? {
    return ContactRemarkCache.getRemarkAvatar(this.id)?.takeIf { it.isNotEmpty() }
        ?: remarkAvatar?.takeIf { it.isNotEmpty() }
        ?: groupMemberContactor?.remarkAvatar?.takeIf { it.isNotEmpty() }
        ?: avatar
}

fun getFirstNonEmptyValue(vararg values: String?): String {
    return values.firstOrNull { !it.isNullOrEmpty() } ?: ""
}





fun String.formatBase58Id(showPrefix: Boolean = true): String {
    return try {
        val cleanId = this.removePrefix("+")
        val numericId = cleanId.toLong()
        val base58Encoded = Base58.encode(numericId)
        if (showPrefix) "TT-$base58Encoded" else base58Encoded
    } catch (e: Exception) {
        L.e { "[ContactorUtil] formatContactId fail for id: $this, error: ${e.stackTraceToString()}" }
        if (showPrefix) "TT-${this.takeLast(7)}" else this.takeLast(7) 
    }
}
