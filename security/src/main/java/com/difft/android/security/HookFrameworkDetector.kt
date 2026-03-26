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

    /**
     * 先按已确认特征包名检测，后续可持续追加。
     */
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

    /**
     * 通过当前进程已加载映射信息（/proc/self/maps）匹配常见 Hook 框架关键字；
     *
     * @return true 表示检测到 Hook/注入框架风险
     */
    fun isHookFrameworkDetected(): Boolean {
        val isMagiskInstalled = isMagiskInstalled()
        val isHookFrameworkDetected = hasKeywordInProcMaps(hookFrameworkKeywords)
        val isHookFrameworkDetectedByStackTrace = hasSuspiciousStackTrace()
        L.i { "[security] HookFrameworkDetector: magiskInstalled=$isMagiskInstalled, hookFrameworkDetected=$isHookFrameworkDetected, hookFrameworkDetectedByStackTrace=$isHookFrameworkDetectedByStackTrace"}
        return isMagiskInstalled || isHookFrameworkDetected || isHookFrameworkDetectedByStackTrace
    }

    /**
     * 检测系统是否安装了 Magisk。
     * /data/adb/magisk 为常见安装路径，存在即视为高风险环境。
     */
    private fun isMagiskInstalled(): Boolean {
        return magiskPaths.any { path ->
            try {
                File(path).exists()
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 检测当前线程调用栈中是否出现已知 Hook 包名前缀。
     */
    fun hasSuspiciousStackTrace(): Boolean {
        val stackTrace = Throwable().stackTrace
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
