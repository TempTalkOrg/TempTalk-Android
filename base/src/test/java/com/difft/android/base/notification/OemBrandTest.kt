package com.difft.android.base.notification

import org.junit.Test
import kotlin.test.assertEquals

/** #1350: one brand detector replaces the equality and substring variants. */
class OemBrandTest {

    private fun detect(brand: String, manufacturer: String, miui: String = "", hyper: String = "") =
        OemBrand.detect(brand, manufacturer, miui, hyper)

    @Test
    fun `sub-brands resolve to their parent skin`() {
        assertEquals(OemBrand.XIAOMI, detect("Redmi", "Xiaomi"))
        assertEquals(OemBrand.XIAOMI, detect("POCO", "Xiaomi"))
        assertEquals(OemBrand.OPPO, detect("OnePlus", "OnePlus"))
        assertEquals(OemBrand.OPPO, detect("realme", "realme"))
        assertEquals(OemBrand.OPPO, detect("OPPO", "OPPO"))
    }

    @Test
    fun `vivo and iQOO are domestic vendors`() {
        assertEquals(OemBrand.VIVO, detect("vivo", "vivo"))
        assertEquals(OemBrand.VIVO, detect("iQOO", "vivo"))
        assertEquals(true, OemBrand.VIVO.isChinaOem)
    }

    @Test
    fun `huawei and honor stay apart even on old honor-branded huawei hardware`() {
        assertEquals(OemBrand.HUAWEI, detect("HUAWEI", "HUAWEI"))
        assertEquals(OemBrand.HUAWEI, detect("honor", "HUAWEI"))
        assertEquals(OemBrand.HONOR, detect("HONOR", "HONOR"))
    }

    @Test
    fun `MIUI and HyperOS properties win over the labels`() {
        assertEquals(OemBrand.XIAOMI, detect("Blackview", "Blackview", miui = "V14"))
        assertEquals(OemBrand.XIAOMI, detect("anything", "anything", hyper = "OS2.0"))
    }

    @Test
    fun `non-domestic brands do not count as China OEMs`() {
        assertEquals(OemBrand.SAMSUNG, detect("samsung", "samsung"))
        assertEquals(OemBrand.OTHER, detect("google", "Google"))
        assertEquals(false, OemBrand.SAMSUNG.isChinaOem)
        assertEquals(false, OemBrand.OTHER.isChinaOem)
        assertEquals(OemBrand.MEIZU, detect("Meizu", "Meizu"))
    }

    @Test
    fun `other vendor skins are grouped and count as China OEMs`() {
        listOf("blackshark", "letv", "asus").forEach {
            assertEquals(OemBrand.OTHER_VENDOR, detect(it, it), it)
        }
        assertEquals(true, OemBrand.OTHER_VENDOR.isChinaOem)
    }
}
