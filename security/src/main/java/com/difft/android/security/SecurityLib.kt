package com.difft.android.security

import android.content.Context
import android.os.Process
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object SecurityLib {

    private val APP_SIGN_SHA256_LIST = setOf(
        "b4e071def9a09fbdab690d0aa0583a2f62e7cdaa96a2d167e3fa8ceeb4853e3e",
        "53c73dd8561d756ad3c162a12ab079357de76f5c6956a2d7e701597e3c4282d5"
    )
    private const val TRACER_PID_MONITOR_INTERVAL_MS = 15_000L

    @Volatile
    private var tracerPidMonitorJob: Job? = null

    /**
     * 校验"待安装的 APK 文件"（尚未安装）的签名证书是否在硬编码白名单内。
     *
     * 应用内升级时，下载 URL 与 hash 均由服务器/CDN 控制，仅校验 hash 无法防御
     * 服务器被攻破后下发恶意 APK。安装前调用本方法做签名证书白名单强校验。
     *
     * @param apkPath 已下载到本地的 APK 文件绝对路径
     * @param expectedPackageName 期望的包名（一般传入当前应用包名），为空则不校验
     * @return true 表示签名证书在白名单内且包名匹配，可安全安装
     */
    @JvmStatic
    @JvmOverloads
    fun checkApkFileSign(
        context: Context,
        apkPath: String,
        expectedPackageName: String? = null,
    ): Boolean {
        return try {
            SignatureVerifier.checkApkFileSign(
                context,
                apkPath,
                APP_SIGN_SHA256_LIST,
                expectedPackageName,
            )
        } catch (e: Exception) {
            L.w(e) { "[SecurityLib] checkApkFileSign error" }
            false
        }
    }

    @JvmStatic
    fun checkEmulator(): Boolean {
        return EmulatorDetector.isSafe()
    }

    @JvmStatic
    fun checkRoot(): Boolean {
        return RootDetector.isSafe()
    }

    @JvmStatic
    fun checkDebuggerConnected(): Boolean {
        return DebuggerDetector.isDebuggerConnected()
    }

    /**
     * 启动 TracerPid 轮询：每 15 秒检测一次，命中后结束应用进程。
     */
    @JvmStatic
    fun startTracerPidMonitor() {
        if (tracerPidMonitorJob?.isActive == true) {
            return
        }
        tracerPidMonitorJob = appScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (DebuggerDetector.isTracerPidDetected()) {
                    L.e { "[security] SecurityLib TracerPid detected, terminating app process." }
                    terminateAppProcess()
                    break
                }
                delay(TRACER_PID_MONITOR_INTERVAL_MS)
            }
        }
    }

    /**
     * true 表示检测到 Hook 框架/注入风险（如 xposed/frida/zygisk 等特征）。
     */
    @JvmStatic
    fun checkHookFramework(): Boolean {
        return HookFrameworkDetector.isHookFrameworkDetected()
    }

    /**
     * true 表示当前线程栈中出现已知 Hook 包名。
     */
    @JvmStatic
    fun checkHookStackTrace(): Boolean {
        return HookFrameworkDetector.hasSuspiciousStackTrace()
    }

    /**
     * True when the given stack trace contains a known hook-framework package
     * (lets ANR-report filtering reuse the same framework list).
     */
    @JvmStatic
    fun checkHookStackTrace(stackTrace: Array<StackTraceElement>): Boolean {
        return HookFrameworkDetector.containsHookFrame(stackTrace)
    }

    fun terminateAppProcess(delayMillis: Long = 3000L) {
        appScope.launch(Dispatchers.Default) {
            delay(delayMillis)
            Process.killProcess(Process.myPid())
        }
    }
}
