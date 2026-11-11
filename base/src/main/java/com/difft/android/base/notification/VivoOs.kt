package com.difft.android.base.notification

import android.content.Intent


object VivoOs {

    



    const val MIN_DIRECT_MAJOR = 6

    private const val PERMISSION_DETAIL_ACTION = "permission.intent.action.softPermissionDetail"
    private const val PERMISSION_PACKAGE = "com.vivo.permissionmanager"
    private const val DISPLAY_ID_PROP = "ro.vivo.os.build.display.id"
    private val ORIGIN_OS = Regex("""^OriginOS\s+(\d+)""", RegexOption.IGNORE_CASE)

    
    fun parseOriginMajor(displayId: String): Int? =
        ORIGIN_OS.find(displayId.trim())?.groupValues?.get(1)?.toIntOrNull()

    
    fun supportsDirectPermissionPage(originMajor: Int?): Boolean = originMajor != null && originMajor >= MIN_DIRECT_MAJOR

    
    fun currentOriginMajor(): Int? = parseOriginMajor(SystemProps.get(DISPLAY_ID_PROP))

    
    fun permissionDetailIntent(pkg: String): Intent =
        Intent(PERMISSION_DETAIL_ACTION).setPackage(PERMISSION_PACKAGE).putExtra("packagename", pkg)
}
