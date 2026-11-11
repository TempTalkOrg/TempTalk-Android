package com.difft.android.base.notification

import java.util.UUID
import javax.inject.Inject


data class NotificationTestCallTicket(
    val runId: String,
    val postedAt: Long,
    val fingerprint: String,
    val expiresAt: Long,
    val status: Status = Status.PENDING,
) {
    enum class Status { PENDING, TIMEOUT, SHOWN }

    fun encode(): String = "1|$runId|$postedAt|$fingerprint|$expiresAt|${status.name}"

    fun isValidAt(nowMs: Long): Boolean = nowMs in postedAt..expiresAt

    companion object {
        const val LIFETIME_MS = 60_000L
        private const val MAX_SIZE = 2_048

        fun decode(raw: String?): NotificationTestCallTicket? {
            if (raw == null || raw.length > MAX_SIZE) return null
            val parts = raw.split('|')
            if (parts.size != 6 || parts[0] != "1" || !validNotificationTestId(parts[1])) return null
            val posted = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val fingerprint = parts[3].takeIf { it.isNotEmpty() } ?: return null
            val expires = parts[4].toLongOrNull()?.takeIf { it > posted && it - posted <= LIFETIME_MS } ?: return null
            val status = Status.entries.firstOrNull { it.name == parts[5] } ?: return null
            return NotificationTestCallTicket(parts[1], posted, fingerprint, expires, status)
        }
    }
}

fun validNotificationTestId(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)


class NotificationTestClock @Inject constructor() {
    fun nowMs(): Long = System.currentTimeMillis()
}

enum class NotificationTestCallUpdate { RECORDED, ALREADY_SHOWN, REJECTED, PERSISTENCE_FAILED }
