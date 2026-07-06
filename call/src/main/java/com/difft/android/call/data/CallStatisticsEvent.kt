package com.difft.android.call.data

/**
 * Typed event definitions for call statistics logging.
 * Each subclass maps to a server-side event name and carries its own detail fields.
 */
sealed class CallStatisticsEvent(val eventName: String) {

    abstract fun toDetails(): Map<String, String>

    data class ConnectFail(
        val mode: String,
        val nodeType: String,
        val ip: String,
        val domain: String,
        val errorMsg: String,
    ) : CallStatisticsEvent(EVENT_NAME) {
        companion object {
            const val EVENT_NAME = "call_connect_fail"
        }

        override fun toDetails() = mapOf(
            "mode" to mode,
            "node_type" to nodeType,
            "ip" to ip,
            "domain" to domain,
            "error_msg" to errorMsg,
        )
    }

    data class RoomReconnectFail(
        val errorMsg: String,
    ) : CallStatisticsEvent(EVENT_NAME) {
        companion object {
            const val EVENT_NAME = "room_reconnect_fail"
        }

        override fun toDetails() = mapOf("error_msg" to errorMsg)
    }

    data class ConfigRefreshFail(
        val errorCode: String,
        val errorMsg: String,
    ) : CallStatisticsEvent(EVENT_NAME) {
        companion object {
            const val EVENT_NAME = "config_refresh_fail"
        }

        override fun toDetails() = mapOf(
            "error_code" to errorCode,
            "error_msg" to errorMsg,
        )
    }

    data class ChannelDowngrade(
        val mode: String,
        val nodeType: String,
        val ip: String,
        val domain: String,
        val errorMsg: String,
    ) : CallStatisticsEvent(EVENT_NAME) {
        companion object {
            const val EVENT_NAME = "channel_downgrade"
        }

        override fun toDetails() = mapOf(
            "mode" to mode,
            "node_type" to nodeType,
            "ip" to ip,
            "domain" to domain,
            "error_msg" to errorMsg,
        )
    }
}
