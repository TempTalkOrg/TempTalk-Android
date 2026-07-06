package com.difft.android.base.storage

import com.difft.android.base.storage.schema.GlobalConfigData
import com.difft.android.base.storage.schema.UserAuthData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Unit tests for [UnavailableDataStore] — the degraded, zero-I/O stub returned by the
 * storage providers when the Keystore cannot build the AEAD (crash 8d61a948).
 *
 * Pure JVM (no Android, no Robolectric) — the stub holds only an immutable `empty` value.
 *
 * Locks the contract from design §"`UnavailableDataStore` 接口契约":
 *  - T1: `data` emits the constructed `empty`.
 *  - T2: `data` re-emits `empty` on repeated reads (collect twice → both `empty`).
 *  - T3: `updateData` returns `transform(empty)`.
 *  - T4: `updateData` never throws (R2) and has no side effects.
 *  - T5: `updateData` is a no-op on the stub — `data` still emits the original `empty`.
 */
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
        // The original empty constant must remain untouched.
        assertEquals("", UserAuthData.EMPTY.baseAuth)
    }

    @Test
    fun `T4 updateData never throws and has no side effects`() = runTest {
        val store = UnavailableDataStore(GlobalConfigData.EMPTY)
        // Two no-op-style updates back-to-back must both succeed without throwing.
        val r1 = store.updateData { it }
        val r2 = store.updateData { it.copy(config = "x") }
        assertSame(GlobalConfigData.EMPTY, r1)
        assertEquals("x", r2.config)
    }

    @Test
    fun `T5 updateData is a no-op - data still emits the original empty`() = runTest {
        val store = UnavailableDataStore(GlobalConfigData.EMPTY)
        store.updateData { it.copy(config = "ignored") }
        // updateData did not persist anything; data still emits the pristine empty.
        assertEquals("", store.data.first().config)
        assertSame(GlobalConfigData.EMPTY, store.data.first())
    }
}
