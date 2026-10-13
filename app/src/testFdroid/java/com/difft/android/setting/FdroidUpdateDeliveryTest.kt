package com.difft.android.setting

import com.difft.android.setting.di.UpdateDeliveryModule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FdroidUpdateDeliveryTest {

    @Test
    fun updateDelivery_opensFDroid() {
        val delivery = UpdateDeliveryModule.provideUpdateDelivery()

        assertIs<StoreUpdateDelivery>(delivery)
        assertEquals(AppStore.FDroid, delivery.store)
    }
}
