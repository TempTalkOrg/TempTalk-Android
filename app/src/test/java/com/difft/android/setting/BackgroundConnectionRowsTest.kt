package com.difft.android.setting

import com.difft.android.base.notification.OemBrand
import com.difft.android.chat.R
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


class BackgroundConnectionRowsTest {

    private val generic = listOf(BgRow.BACKGROUND_RESTRICTION, BgRow.BATTERY_OPTIMIZATION, BgRow.DATA_SAVER)

    private fun flags(
        autoStart: Boolean = false,
        background: Boolean = false,
        exactAlarm: Boolean = false,
        restriction: Boolean = true,
    ) = BgRowFlags(
        showBackgroundRestriction = restriction,
        canOpenAutoStart = autoStart,
        canOpenBackgroundActivity = background,
        exactAlarmApplicable = exactAlarm,
    )

    @Test
    fun `vivo is direct only when both rows are available`() {
        assertTrue(isVivoDirectRows(OemBrand.VIVO, canOpenAutoStart = true, canOpenBackgroundActivity = true))
        assertFalse(isVivoDirectRows(OemBrand.VIVO, canOpenAutoStart = true, canOpenBackgroundActivity = false))
        assertFalse(isVivoDirectRows(OemBrand.VIVO, canOpenAutoStart = false, canOpenBackgroundActivity = true))
        assertFalse(isVivoDirectRows(OemBrand.OPPO, canOpenAutoStart = true, canOpenBackgroundActivity = true))
    }

    @Test
    fun `the vendor guide row is only for guide-only China skins`() {
        assertFalse(showVendorGuide(OemBrand.VIVO, vivoDirect = true))
        assertTrue(showVendorGuide(OemBrand.VIVO, vivoDirect = false))
        assertTrue(showVendorGuide(OemBrand.MEIZU, vivoDirect = false))
        assertTrue(showVendorGuide(OemBrand.OTHER_VENDOR, vivoDirect = false))
        for (brand in listOf(OemBrand.HUAWEI, OemBrand.HONOR, OemBrand.OPPO)) {
            assertTrue(showVendorGuide(brand, vivoDirect = false), brand.name)
        }
        for (brand in listOf(OemBrand.XIAOMI, OemBrand.SAMSUNG, OemBrand.OTHER)) {
            assertFalse(showVendorGuide(brand, vivoDirect = false), brand.name)
        }
    }

    @Test
    fun `Xiaomi lists its rows after the generic ones`() {
        assertEquals(
            generic + listOf(BgRow.AUTO_START, BgRow.BACKGROUND_ACTIVITY),
            backgroundRowOrder(OemBrand.XIAOMI, flags(autoStart = true, background = true)),
        )
        
        assertEquals(generic + BgRow.AUTO_START, backgroundRowOrder(OemBrand.XIAOMI, flags(autoStart = true)))
    }

    @Test
    fun `vivo with both direct rows has no vendor guide row`() {
        assertEquals(
            generic + listOf(BgRow.AUTO_START, BgRow.BACKGROUND_POWER),
            backgroundRowOrder(OemBrand.VIVO, flags(autoStart = true, background = true)),
        )
    }

    @Test
    fun `vivo without both direct rows keeps the vendor guide as its only guide`() {
        assertEquals(
            generic + listOf(BgRow.AUTO_START, BgRow.VENDOR_GUIDE),
            backgroundRowOrder(OemBrand.VIVO, flags(autoStart = true)),
        )
        assertEquals(generic + BgRow.VENDOR_GUIDE, backgroundRowOrder(OemBrand.VIVO, flags()))
    }

    @Test
    fun `Huawei, Honor and OPPO keep the guide-backed vendor row last`() {
        for (brand in listOf(OemBrand.HUAWEI, OemBrand.HONOR, OemBrand.OPPO)) {
            assertEquals(generic + BgRow.VENDOR_GUIDE, backgroundRowOrder(brand, flags()), brand.name)
        }
    }

    @Test
    fun `guide-only China skins show just the vendor guide row last`() {
        for (brand in listOf(OemBrand.MEIZU, OemBrand.OTHER_VENDOR)) {
            assertEquals(generic + BgRow.VENDOR_GUIDE, backgroundRowOrder(brand, flags()), brand.name)
        }
    }

    @Test
    fun `Samsung has no vendor row because its background usage switch is the generic restriction row`() {
        assertEquals(generic, backgroundRowOrder(OemBrand.SAMSUNG, flags()))
    }

    @Test
    fun `other brands only get the generic rows`() {
        assertEquals(generic, backgroundRowOrder(OemBrand.OTHER, flags()))
    }

    @Test
    fun `exact alarm and the restriction row follow their flags`() {
        assertEquals(
            listOf(BgRow.BATTERY_OPTIMIZATION, BgRow.DATA_SAVER, BgRow.EXACT_ALARM),
            backgroundRowOrder(OemBrand.OTHER, flags(exactAlarm = true, restriction = false)),
        )
    }

    @Test
    fun `every row has exactly one position`() {
        for (brand in OemBrand.entries) {
            val rows = backgroundRowOrder(brand, flags(autoStart = true, background = true, exactAlarm = true))
            assertEquals(rows.distinct(), rows, brand.name)
        }
    }

    @Test
    fun `the vendor guide row appears at most once per brand`() {
        for (brand in OemBrand.entries) {
            for (direct in listOf(true, false)) {
                val rows = backgroundRowOrder(brand, flags(autoStart = direct, background = direct))
                assertTrue(rows.count { it == BgRow.VENDOR_GUIDE } <= 1, "${brand.name} direct=$direct")
            }
        }
    }

    @Test
    fun `every vendor row names what to set`() {
        assertEquals(BgHint(R.string.background_hint_auto_start_xiaomi, takesAppName = true), hintFor(BgRow.AUTO_START, OemBrand.XIAOMI))
        assertEquals(BgHint(R.string.background_hint_power_policy_xiaomi), hintFor(BgRow.BACKGROUND_ACTIVITY, OemBrand.XIAOMI))
        assertEquals(BgHint(R.string.background_hint_vendor_guide), hintFor(BgRow.VENDOR_GUIDE, OemBrand.HUAWEI))
        assertNull(hintFor(BgRow.BATTERY_OPTIMIZATION, OemBrand.XIAOMI))
        assertEquals(BgHint(R.string.background_hint_auto_start_vivo), hintFor(BgRow.AUTO_START, OemBrand.VIVO))
        assertEquals(BgHint(R.string.background_power_hint_vivo), hintFor(BgRow.BACKGROUND_POWER, OemBrand.VIVO))
    }


    @Test
    fun `rows that read system state have no hint`() {
        for (row in listOf(BgRow.BACKGROUND_RESTRICTION, BgRow.BATTERY_OPTIMIZATION, BgRow.DATA_SAVER, BgRow.EXACT_ALARM)) {
            assertNull(hintFor(row, OemBrand.XIAOMI), row.name)
        }
    }

    @Test
    fun `restricted and unverifiable rows warn while normal ones stay secondary`() {
        val bad = BgCheckState(isBackgroundRestricted = true, isIgnoringBatteryOpt = false, isDataSaverRestricted = true, exactAlarmState = ExactAlarmState.NotGranted)
        val good = BgCheckState(isBackgroundRestricted = false, isIgnoringBatteryOpt = true, isDataSaverRestricted = false, exactAlarmState = ExactAlarmState.Granted)
        for (row in listOf(BgRow.BACKGROUND_RESTRICTION, BgRow.BATTERY_OPTIMIZATION, BgRow.DATA_SAVER, BgRow.EXACT_ALARM)) {
            assertTrue(statusOf(row, bad).warning, "bad $row")
            assertFalse(statusOf(row, good).warning, "good $row")
        }
        assertEquals(R.string.battery_optimization_not_ignored, statusOf(BgRow.BATTERY_OPTIMIZATION, bad).res)
        assertEquals(R.string.battery_optimization_ignored, statusOf(BgRow.BATTERY_OPTIMIZATION, good).res)
        for (row in listOf(BgRow.AUTO_START, BgRow.VENDOR_GUIDE)) {
            assertEquals(BgStatus(null, warning = false), statusOf(row, good), row.name)
        }
    }

}
