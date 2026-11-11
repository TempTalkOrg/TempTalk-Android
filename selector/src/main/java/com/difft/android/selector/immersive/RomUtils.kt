package com.difft.android.selector.immersive

import android.os.Build
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import java.util.Locale





object RomUtils {

    private val ROM_SAMSUNG = arrayOf("samsung")
    private const val UNKNOWN = "unknown"

    


    fun isSamsung(): Boolean {
        val brand = getBrand()
        val manufacturer = getManufacturer()
        return isRightRom(brand, manufacturer, *ROM_SAMSUNG)
    }

    private fun isRightRom(brand: String, manufacturer: String, vararg names: String): Boolean {
        for (name in names) {
            if (brand.contains(name) || manufacturer.contains(name)) {
                return true
            }
        }
        return false
    }

    private fun getManufacturer(): String {
        try {
            val manufacturer = Build.MANUFACTURER
            if (!TextUtils.isEmpty(manufacturer)) {
                return manufacturer.lowercase(Locale.ROOT)
            }
        } catch (ignore: Throwable) {
            L.w(ignore) { "[RomUtils] getManufacturer failed" }
        }
        return UNKNOWN
    }

    private fun getBrand(): String {
        try {
            val brand = Build.BRAND
            if (!TextUtils.isEmpty(brand)) {
                return brand.lowercase(Locale.ROOT)
            }
        } catch (ignore: Throwable) {
            L.w(ignore) { "[RomUtils] getBrand failed" }
        }
        return UNKNOWN
    }
}
