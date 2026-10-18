package com.difft.android.setting

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundConnectionHelpLinkTest {

    @Test
    fun chineseLocaleAndManufacturerAreNormalised() {
        val url = BackgroundConnectionHelpLink.build(
            language = "zh",
            manufacturer = "Xiaomi",
            model = "23124RN87I",
            sdkInt = 34,
            source = BackgroundConnectionHelpLink.Source.BACKGROUND_CONNECTION,
        )
        assertEquals(
            "${BackgroundConnectionHelpLink.BASE_URL}?lng=zh&brand=xiaomi&model=23124RN87I&sdk=34&from=connection",
            url,
        )
    }

    @Test
    fun nonChineseLocaleFallsBackToEnglishAndEncodesModel() {
        val url = BackgroundConnectionHelpLink.build(
            language = "de",
            manufacturer = "HUAWEI",
            model = "Mate 60 Pro",
            sdkInt = 33,
            source = BackgroundConnectionHelpLink.Source.NOTIFICATION_SETTINGS,
        )
        assertEquals(
            "${BackgroundConnectionHelpLink.BASE_URL}?lng=en&brand=huawei&model=Mate+60+Pro&sdk=33&from=notification",
            url,
        )
    }

    @Test
    fun blankManufacturerAndModelAreOmitted() {
        val url = BackgroundConnectionHelpLink.build(
            language = "en",
            manufacturer = " ",
            model = "",
            sdkInt = 30,
            source = BackgroundConnectionHelpLink.Source.BACKGROUND_CONNECTION,
        )
        assertEquals("${BackgroundConnectionHelpLink.BASE_URL}?lng=en&sdk=30&from=connection", url)
    }
}
