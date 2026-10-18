package com.difft.android.base.notification

import android.os.Build
import com.difft.android.base.log.lumberjack.L

/**
 * One diagnostic line per settings jump: which fix, on which skin and SDK, and how far down the
 * fallback chain it got. Device facts only, no user data, so a tester's log shows which vendor
 * pages actually open.
 */
internal object OemJumpLog {

    private const val MAX_OS_LENGTH = 24

    private val osVersion: String by lazy {
        listOf(
            "ro.mi.os.version.name",
            "ro.miui.ui.version.name",
            "ro.build.version.opporom",
            "ro.vivo.os.build.display.id",
        ).firstNotNullOfOrNull { SystemProps.get(it).takeIf(String::isNotBlank) }
            ?.let { shorten(it) } ?: "-"
    }

    internal fun shorten(value: String): String = value.trim().replace(Regex("\\s+"), "_").take(MAX_OS_LENGTH)

    /** [opened] is the 1-based chain index that started, or 0 when none did. */
    fun log(fix: String, opened: Int, count: Int, target: String) {
        L.i {
            "[OemJump] fix=$fix brand=${OemBrand.current()} os=$osVersion sdk=${Build.VERSION.SDK_INT} " +
                "opened=$opened/$count target=$target"
        }
    }
}
