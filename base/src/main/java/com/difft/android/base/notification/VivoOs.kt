package com.difft.android.base.notification

import android.content.Intent

/** vivo OriginOS version detection for the per-app permission page jump. */
object VivoOs {

    /**
     * First OriginOS major version whose permission page was verified to open directly (OriginOS 6,
     * Android 16). Earlier OriginOS and Funtouch builds keep the setup guide.
     */
    const val MIN_DIRECT_MAJOR = 6

    private const val PERMISSION_DETAIL_ACTION = "permission.intent.action.softPermissionDetail"
    private const val PERMISSION_PACKAGE = "com.vivo.permissionmanager"
    private const val DISPLAY_ID_PROP = "ro.vivo.os.build.display.id"
    private val ORIGIN_OS = Regex("""^OriginOS\s+(\d+)""", RegexOption.IGNORE_CASE)

    /** The OriginOS major version in [displayId] ("OriginOS 6" is 6), or null for Funtouch or unknown text. */
    fun parseOriginMajor(displayId: String): Int? =
        ORIGIN_OS.find(displayId.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Whether [originMajor] is a version whose permission page jump is verified. */
    fun supportsDirectPermissionPage(originMajor: Int?): Boolean = originMajor != null && originMajor >= MIN_DIRECT_MAJOR

    /** The OriginOS major version of this device, or null. */
    fun currentOriginMajor(): Int? = parseOriginMajor(SystemProps.get(DISPLAY_ID_PROP))

    /** vivo's all-permissions page for [pkg]; its device-management section holds the Autostart switch. */
    fun permissionDetailIntent(pkg: String): Intent =
        Intent(PERMISSION_DETAIL_ACTION).setPackage(PERMISSION_PACKAGE).putExtra("packagename", pkg)
}
