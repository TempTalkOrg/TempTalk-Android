package com.difft.android.base.storage

import com.difft.android.base.storage.schema.GlobalConfigData
import com.difft.android.base.storage.schema.UserAuthData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame


class UnavailableDataStoreTest {

    @Test
    fun `T1 data emits the constructed empty value`() = runTest {
        val store = UnavailableDataStore(UserAuthData.EMPTY)
        assertSame(UserAuthData.EMPTY, store.data.first())
    }

    @Test
    fun `T2 data re-emits empty on repeated reads`() = runTest {
        val store = UnavailableDataStore(UserAuthData.EMPTY)
        val first = store.data.first()
        val second = store.data.first()
        assertSame(UserAuthData.EMPTY, first)
        assertSame(UserAuthData.EMPTY, second)
    }

    @Test
    fun `T3 updateData returns transform applied to empty`() = runTest {
        val store = UnavailableDataStore(UserAuthData.EMPTY)
        val result = store.updateData { it.copy(baseAuth = "changed") }
        assertEquals("changed", result.baseAuth)
        
        assertEquals("", UserAuthData.EMPTY.baseAuth)
    }

    @Test
    fun `T4 updateData never throws and has no side effects`() = runTest {
        val store = UnavailableDataStore(GlobalConfigData.EMPTY)
        
        val r1 = store.updateData { it }
        val r2 = store.updateData { it.copy(config = "x") }
        assertSame(GlobalConfigData.EMPTY, r1)
        assertEquals("x", r2.config)
    }

    @Test
    fun `T5 updateData is a no-op - data still emits the original empty`() = runTest {
        val store = UnavailableDataStore(GlobalConfigData.EMPTY)
        store.updateData { it.copy(config = "ignored") }
        
        assertEquals("", store.data.first().config)
        assertSame(GlobalConfigData.EMPTY, store.data.first())
    }
}
