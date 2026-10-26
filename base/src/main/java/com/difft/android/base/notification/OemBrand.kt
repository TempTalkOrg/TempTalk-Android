package com.difft.android.base.notification

import android.os.Build
import java.util.Locale

/**
 * The Android skin family the device runs, detected once for every notification/call check.
 *
 * Detection is a substring match on MANUFACTURER first, then BRAND, so sub-brands resolve to their
 * parent skin (Redmi/POCO to Xiaomi, OnePlus/realme to OPPO, iQOO to vivo). MIUI/HyperOS system
 * properties win over the labels because rebranded Xiaomi devices keep the properties.
 */
enum class OemBrand(val isChinaOem: Boolean) {
    XIAOMI(true),
    HUAWEI(true),
    HONOR(true),
    OPPO(true),
    VIVO(true),
    MEIZU(true),

    /** Other skins with their own vendor settings (Black Shark, Letv, Asus); guide page only. */
    OTHER_VENDOR(true),
    SAMSUNG(false),
    OTHER(false);

    /**
     * True only for brands whose banner, lock-screen and sound switches are verified to map to
     * standard channel fields; elsewhere a standard "on" reading cannot prove the vendor switches are
     * on. Samsung's app-level lock screen, pop-up and sound switches live outside the channels (#1350).
     */
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

        /** Pure detection, exposed for tests. */
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

        /** The brand of this device; resolved once per process. */
        fun current(): OemBrand = cached ?: detect(
            brand = Build.BRAND.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            miuiVersion = SystemProps.get("ro.miui.ui.version.name"),
            hyperOsVersion = SystemProps.get("ro.mi.os.version.name"),
        ).also { cached = it }
    }
}
