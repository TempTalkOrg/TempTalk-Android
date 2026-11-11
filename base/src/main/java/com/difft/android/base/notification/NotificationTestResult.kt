package com.difft.android.base.notification

import java.util.concurrent.TimeUnit


enum class NotificationTestKind(val logName: String) {
    BANNER("banner"),
    LOCKSCREEN("lockscreen"),
    CALL("call"),
}

enum class NotificationTestOutcome(internal val code: String) {
    OK("ok"),
    FAIL("fail"),
}






data class NotificationTestResult(
    val outcome: NotificationTestOutcome,
    val epochMs: Long,
    val fingerprint: String,
) {
    fun encode(): String = "${outcome.code}$SEPARATOR$epochMs$SEPARATOR$fingerprint"

    
    fun isValid(currentFingerprint: String, nowMs: Long): Boolean =
        fingerprint == currentFingerprint && (nowMs - epochMs) in 0..MAX_AGE_MS

    companion object {
        val MAX_AGE_MS: Long = TimeUnit.DAYS.toMillis(30)
        private const val SEPARATOR = '|'
        private const val PARTS = 3

        
        fun decode(raw: String?): NotificationTestResult? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(SEPARATOR)
            if (parts.size != PARTS) return null
            val outcome = NotificationTestOutcome.entries.firstOrNull { it.code == parts[0] } ?: return null
            val epochMs = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val fingerprint = parts[2].takeIf { it.isNotEmpty() } ?: return null
            return NotificationTestResult(outcome, epochMs, fingerprint)
        }
    }
}


data class NotificationTestResults(
    val banner: NotificationTestResult? = null,
    val lockscreen: NotificationTestResult? = null,
    val call: NotificationTestResult? = null,
) {
    operator fun get(kind: NotificationTestKind): NotificationTestResult? = when (kind) {
        NotificationTestKind.BANNER -> banner
        NotificationTestKind.LOCKSCREEN -> lockscreen
        NotificationTestKind.CALL -> call
    }

    companion object {
        val NONE = NotificationTestResults()

        
        fun validOf(
            stored: Map<NotificationTestKind, NotificationTestResult>,
            input: ReadinessInput,
            buildFingerprint: String,
            nowMs: Long,
        ): NotificationTestResults {
            fun valid(kind: NotificationTestKind): NotificationTestResult? = stored[kind]?.takeIf {
                it.isValid(NotificationTestFingerprint.of(kind, input, buildFingerprint), nowMs)
            }
            return NotificationTestResults(
                banner = valid(NotificationTestKind.BANNER),
                lockscreen = valid(NotificationTestKind.LOCKSCREEN),
                call = valid(NotificationTestKind.CALL),
            )
        }
    }
}
