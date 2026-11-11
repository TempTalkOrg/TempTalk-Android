package com.difft.android.base.notification

import java.security.MessageDigest







object NotificationTestFingerprint {

    private const val HASH_CHARS = 16

    fun of(kind: NotificationTestKind, input: ReadinessInput, buildFingerprint: String): String {
        val channel = if (kind == NotificationTestKind.CALL) input.callChannel else input.messageChannel
        val parts = buildList {
            add(buildFingerprint)
            add("n1=${input.postNotificationsGranted && input.appNotificationsEnabled}")
            add("group=${input.messageGroupBlocked}")
            add(channel?.let { "ch=${it.importance},${it.hasSound},${it.lockscreenVisibility}" } ?: "ch=none")
            if (kind == NotificationTestKind.CALL) add("fsi=${input.canUseFullScreenIntent}")
        }
        return sha256Hex(parts.joinToString("\n")).take(HASH_CHARS)
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
