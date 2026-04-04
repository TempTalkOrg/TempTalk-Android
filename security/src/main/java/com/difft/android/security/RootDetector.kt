package com.difft.android.security

import java.io.File

internal object RootDetector {

    private val rootCheckPaths = arrayOf(
        "/system/app/Superuser.apk",
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/system/xbin/daemonsu"
    )

    fun isSafe(): Boolean {
        return !hasRootPath() && !hasSuByWhich() && !hasInsecureRoSecure() && !hasMagiskSu()
    }

    private fun hasRootPath(): Boolean {
        return rootCheckPaths.any { File(it).exists() }
    }

    private fun hasSuByWhich(): Boolean {
        val output = SecurityRuntime.runShellCommand("which su")
        if (output.isEmpty()) {
            return false
        }
        return output.first().contains("not found").not()
    }

    private fun hasInsecureRoSecure(): Boolean {
        val secure = SecurityRuntime.getSystemProperty("ro.secure")
        return !secure.isNullOrEmpty() && secure.startsWith("0")
    }

    private fun hasMagiskSu(): Boolean {
        val output = SecurityRuntime.runShellCommand("magisk --list")
        if (output.isEmpty()) {
            return false
        }
        return output.any { it.startsWith("su") }
    }
}
