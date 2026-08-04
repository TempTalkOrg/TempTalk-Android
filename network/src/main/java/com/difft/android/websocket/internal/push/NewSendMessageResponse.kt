package com.difft.android.websocket.internal.push

import com.fasterxml.jackson.annotation.JsonProperty

class NewSendMessageResponse() {

    @field:JsonProperty
    var ver: Int = 0

    @field:JsonProperty
    var status: Int = 0

    @field:JsonProperty
    var reason: String? = null

    // Nullable: absent from JSON -> null (see JacksonDeserializationTest). Callers that dereference
    // it (NewSignalServiceMessageSender) use !!, preserving the original Java NPE-on-absent semantics.
    @field:JsonProperty
    var data: Data? = null

    constructor(ver: Int, status: Int, reason: String?, data: Data) : this() {
        this.ver = ver
        this.status = status
        this.reason = reason
        this.data = data
    }

    class Data {

        // Wire key stays "needsSync"; exposed as isNeedsSync so callers keep property access.
        @field:JsonProperty("needsSync")
        var isNeedsSync: Boolean = false

        @field:JsonProperty
        var sequenceId: Long = 0

        @field:JsonProperty
        var systemShowTimestamp: Long = 0

        @field:JsonProperty
        var notifySequenceId: Long = 0

        @field:JsonProperty
        var missing: List<User>? = null

        @field:JsonProperty
        var extra: List<User>? = null

        @field:JsonProperty
        var stale: List<User>? = null

        @field:JsonProperty
        var unavailableUsers: List<UnavailableUser>? = null
    }

    class User {

        // Nullable to match the original Java model: an absent uid stays null (not ""), so callers
        // that collect flagged uids skip it via mapNotNull instead of refreshing an empty uid.
        @field:JsonProperty
        var uid: String? = null

        @field:JsonProperty
        var identityKey: String? = null

        @field:JsonProperty
        var registrationId: Int = 0
    }

    class UnavailableUser {

        @field:JsonProperty
        var uid: String? = null

        @field:JsonProperty
        var reason: String? = null
    }
}
