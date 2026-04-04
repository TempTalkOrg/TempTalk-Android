package com.difft.android.security

import java.io.File

internal object EmulatorDetector {

    private val featureFiles = arrayOf(
        "/system/bin/androVM-prop",
        "/system/bin/microvirt-prop",
        "/system/lib/libdroid4x.so",
        "/system/bin/windroyed",
        "/system/bin/nox-prop",
        "/system/lib/libnoxspeedup.so",
        "/system/bin/ttVM-prop",
        "/data/.bluestacks.prop",
        "/system/bin/duosconfig",
        "/system/etc/xxzs_prop.sh",
        "/system/etc/mumu-configs/device-prop-configs/mumu.config",
        "/system/priv-app/ldAppStore",
        "/system/app/AntStore",
        "/data/vmos.prop",
        "/fstab.titan",
        "/x8.prop",
    )

    private val mapKeywords = arrayOf(
        "libhoudini",
        "com.vmos.pro",
        "com.vmos.app",
        "com.vphonegaga.titan",
        "com.f1player",
    )

    fun isSafe(): Boolean {
        return !hasFeatureFile() && !hasEmulatorBuildInfo() && !hasEmulatorLibrary()
    }

    private fun hasFeatureFile(): Boolean {
        return featureFiles.any { File(it).exists() }
    }

    private fun hasEmulatorBuildInfo(): Boolean {
        val characteristics = SecurityRuntime.getSystemProperty("ro.build.characteristics")
        if (!characteristics.isNullOrEmpty() && characteristics.contains("emulator")) {
            return true
        }

        val fingerprint = SecurityRuntime.getSystemProperty("ro.product.build.fingerprint")
        if (!fingerprint.isNullOrEmpty() && fingerprint.contains("tencent/vbox64tp/")) {
            return true
        }

        val platform = SecurityRuntime.getSystemProperty("ro.board.platform")
        return !platform.isNullOrEmpty() && platform.contains("windows")
    }

    private fun hasEmulatorLibrary(): Boolean {
        val mapsFile = File("/proc/self/maps")
        if (!mapsFile.exists()) {
            return false
        }
        val maps = runCatching { mapsFile.readText() }.getOrNull() ?: return false
        return mapKeywords.any { maps.contains(it) }
    }
}
