package com.difft.android.base.notification

import android.os.Build
import java.util.Locale








enum class OemBrand(val isChinaOem: Boolean) {
    XIAOMI(true),
    HUAWEI(true),
    HONOR(true),
    OPPO(true),
    VIVO(true),
    MEIZU(true),

    
    OTHER_VENDOR(true),
    SAMSUNG(false),
    OTHER(false);

    




    val vendorAlertSwitchesReadable: Boolean get() = !isChinaOem && this != SAMSUNG

    companion object {
        private val KEYWORDS: List<Pair<OemBrand, List<String>>> = listOf(
            XIAOMI to listOf("xiaomi", "redmi", "poco"),
            HUAWEI to listOf("huawei"),
            HONOR to listOf("honor"),
            OPPO to listOf("oppo", "oneplus", "realme"),
            VIVO to listOf("vivo", "iqoo"),
            MEIZU to listOf("meizu"),
            OTHER_VENDOR to listOf("blackshark", "letv", "asus"),
            SAMSUNG to listOf("samsung"),
        )

        @Volatile
        private var cached: OemBrand? = null

        
        fun detect(
            brand: String,
            manufacturer: String,
            miuiVersion: String = "",
            hyperOsVersion: String = "",
        ): OemBrand {
            if (miuiVersion.isNotBlank() || hyperOsVersion.isNotBlank()) return XIAOMI
            val man = manufacturer.lowercase(Locale.ROOT)
            val brd = brand.lowercase(Locale.ROOT)
            return match(man) ?: match(brd) ?: OTHER
        }

        private fun match(label: String): OemBrand? =
            KEYWORDS.firstOrNull { (_, keys) -> keys.any { it in label } }?.first

        
        fun current(): OemBrand = cached ?: detect(
            brand = Build.BRAND.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            miuiVersion = SystemProps.get("ro.miui.ui.version.name"),
            hyperOsVersion = SystemProps.get("ro.mi.os.version.name"),
        ).also { cached = it }
    }
}
