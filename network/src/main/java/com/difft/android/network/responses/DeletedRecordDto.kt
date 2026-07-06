package com.difft.android.network.responses

import com.google.gson.annotations.SerializedName

/**
 * One pending-removal record returned by GET v3/friend/deletedRecords (bare array, unpaged, 15-day window).
 *
 * Wire field names are anchored explicitly with [SerializedName] so the Kotlin field names are
 * decoupled from the server contract (a server-side rename does not silently break parsing, and the
 * mapping is auditable in one place). The server unified the identifier on `uid` and the expiry on
 * `expireTime`; the notify payload ([TTNotifyMessage.Data]) carries the same wire names. Both are
 * parsed independently and mapped into the same internal weak-contact model.
 *
 * [uid] is nullable on purpose: gson populates a Kotlin non-null field with null when the server
 * omits the key or sends a mismatched name, and assigning that null to the non-null
 * `ContactorModel.id` crashes at cold start (NPE in setId). The reconcile filter drops any record
 * whose uid is null/blank, so downstream code only ever sees a valid uid.
 */
data class DeletedRecordDto(
    @SerializedName("uid") val uid: String?,
    @SerializedName("reason") val reason: Int,            // 0=deleted / 1=deregistered (client does not distinguish)
    @SerializedName("name") val name: String?,
    @SerializedName("avatar") val avatar: String?,        // Avatar2 JSON snapshot {"attachmentId":...}
    @SerializedName("deleteTime") val deleteTime: Long,   // entered-weak time, ms UTC
    @SerializedName("expireTime") val expireTime: Long    // absolute expiry, ms UTC
)
