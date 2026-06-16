package com.difft.android.security

import android.annotation.SuppressLint
import java.util.Collections
import java.util.concurrent.TimeUnit

@SuppressLint("PrivateApi")
internal object SecurityRuntime {

    private const val COMMAND_TIMEOUT_MS = 3_000L

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
        return process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
