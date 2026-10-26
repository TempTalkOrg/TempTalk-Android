package com.difft.android.base.notification

import java.security.MessageDigest

/**
 * The readable settings a test result depends on (#1350): the system build, the app-level switch
 * and permission, the MESSAGE group switch, the tested category's importance, sound and lock-screen
 * override, and for the call test the full-screen permission. Any change makes an old result stale,
 * because the vendor switches it vouches for cannot be read and may have changed with it.
 */
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
