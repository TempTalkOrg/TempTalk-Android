package com.difft.android.security

import android.annotation.SuppressLint
import android.os.Build
import java.util.Collections
import java.util.concurrent.TimeUnit

@SuppressLint("PrivateApi")
internal object SecurityRuntime {

    private const val COMMAND_TIMEOUT_MS = 3_000L
    private const val PROCESS_POLL_INTERVAL_MS = 50L

    fun runShellCommand(command: String): List<String> {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val output = Collections.synchronizedList(mutableListOf<String>())
            val readerThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { output.add(it) }
                    }
                }
            }
            readerThread.isDaemon = true
            readerThread.start()

            if (!waitForProcess(process)) {
                process.destroy()
                readerThread.join(100L)
                return emptyList()
            }

            readerThread.join(100L)
            output.toList()
        }.getOrElse { emptyList() }
    }

    private fun waitForProcess(process: Process): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } else {
            val deadlineNs = System.nanoTime() + COMMAND_TIMEOUT_MS * 1_000_000L
            while (runCatching { process.exitValue() }.isFailure) {
                if (System.nanoTime() >= deadlineNs) return false
                Thread.sleep(PROCESS_POLL_INTERVAL_MS)
            }
            true
        }
    }

    private val systemPropertiesClass: Class<*>? by lazy {
        runCatching { Class.forName("android.os.SystemProperties") }.getOrNull()
    }

    fun getSystemProperty(key: String): String? {
        return getPropertyByReflection(key) ?: getPropertyByShell(key)
    }

    private fun getPropertyByReflection(key: String): String? {
        return runCatching {
            val method = systemPropertiesClass?.getMethod("get", String::class.java)
            (method?.invoke(null, key) as? String)?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun getPropertyByShell(key: String): String? {
        return runShellCommand("getprop $key")
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

}
