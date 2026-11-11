package com.difft.android.security

import com.difft.android.base.log.lumberjack.L
import java.io.File

internal object HookFrameworkDetector {

    private val hookFrameworkKeywords = listOf(
        "riru",
        "xposed",
        "frida-agent",
        "edhooker",
        "cydia",
        "libepic",
        "sandhook",
        "libiohook.so",
        "fasthook",
        "edxp.so",
        "com.qihoo.magic",
        "frida",
        "substrate",
        "edxposed",
    )

    private val magiskPaths = listOf(
        "/sbin/.magisk",
        "/data/adb/magisk",
        "/data/adb/magisk.img",
        "/data/adb/magisk.db",
        "/data/adb/modules",
        "/data/user_de/0/com.topjohnwu.magisk",
        "/cache/.disable_magisk"
    )

    
    private val suspiciousStackPackagePrefixes = listOf(
        "com.lian.lhook",
        "de.robv.android.xposed",
        "org.lsposed",
        "com.swift.sandhook",
        "me.weishu.epic",
        "com.saurik.substrate",
        "io.github.libxposed",
        "com.topjohnwu.magisk",
        "com.frida",
        "re.frida"
    )

    




    fun isHookFrameworkDetected(): Boolean {
        val isMagiskInstalled = isMagiskInstalled()
        val isHookFrameworkDetected = hasKeywordInProcMaps(hookFrameworkKeywords)
        val isHookFrameworkDetectedByStackTrace = hasSuspiciousStackTrace()
        L.i { "[security] HookFrameworkDetector: magiskInstalled=$isMagiskInstalled, hookFrameworkDetected=$isHookFrameworkDetected, hookFrameworkDetectedByStackTrace=$isHookFrameworkDetectedByStackTrace"}
        return isMagiskInstalled || isHookFrameworkDetected || isHookFrameworkDetectedByStackTrace
    }

    
    private fun isMagiskInstalled(): Boolean {
        return magiskPaths.any { path ->
            try {
                File(path).exists()
            } catch (_: Exception) {
                false
            }
        }
    }

    
    fun hasSuspiciousStackTrace(): Boolean = containsHookFrame(Throwable().stackTrace)

    



    fun containsHookFrame(stackTrace: Array<StackTraceElement>): Boolean {
        if (stackTrace.isEmpty()) {
            return false
        }
        return stackTrace.any { element ->
            val className = element.className.lowercase()
            suspiciousStackPackagePrefixes.any { className.startsWith(it) }
        }
    }

    private fun hasKeywordInProcMaps(keywords: List<String>): Boolean {
        val mapsFile = File("/proc/self/maps")
        if (!mapsFile.exists()) {
            return false
        }

        val normalizedKeywords = keywords.map { it.lowercase() }
        return runCatching {
            mapsFile.useLines { lines ->
                lines.any { line ->
                    val normalizedLine = line.lowercase()
                    normalizedKeywords.any { key -> normalizedLine.contains(key) }
                }
            }
        }.getOrDefault(false)
    }
}
