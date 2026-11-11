package com.difft.android.selector.permissions

import android.os.Build
import com.difft.android.selector.config.SelectMimeType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue













@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PermissionConfigTest {

    private val app get() = RuntimeEnvironment.getApplication()

    
    @Test
    @Config(sdk = [29, 30, 32])
    fun `read permission array on api 29 to 32 asks for read only`() {
        
        assertTrue(Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2)

        val result = PermissionConfig.getReadPermissionArray(app, SelectMimeType.ofAll())

        assertContentEquals(arrayOf(PermissionConfig.READ_EXTERNAL_STORAGE), result)
        assertFalse(result.contains(PermissionConfig.WRITE_EXTERNAL_STORAGE))
    }

    
    @Test
    @Config(sdk = [28])
    fun `read permission array on api 28 keeps write and read first`() {
        val result = PermissionConfig.getReadPermissionArray(app, SelectMimeType.ofAll())

        assertContentEquals(
            arrayOf(
                PermissionConfig.READ_EXTERNAL_STORAGE,
                PermissionConfig.WRITE_EXTERNAL_STORAGE,
            ),
            result,
        )
        assertEquals(PermissionConfig.READ_EXTERNAL_STORAGE, result[0])
    }

    
    @Test
    @Config(sdk = [26])
    fun `read permission array on min sdk keeps write`() {
        val result = PermissionConfig.getReadPermissionArray(app, SelectMimeType.ofAll())

        assertContentEquals(
            arrayOf(
                PermissionConfig.READ_EXTERNAL_STORAGE,
                PermissionConfig.WRITE_EXTERNAL_STORAGE,
            ),
            result,
        )
    }

    
    @Test
    fun `read permission array on api 29 is the same for every choose mode`() {
        val expected = arrayOf(PermissionConfig.READ_EXTERNAL_STORAGE)

        assertContentEquals(expected, PermissionConfig.getReadPermissionArray(app, SelectMimeType.ofImage()))
        assertContentEquals(expected, PermissionConfig.getReadPermissionArray(app, SelectMimeType.ofVideo()))
        assertContentEquals(expected, PermissionConfig.getReadPermissionArray(app, SelectMimeType.ofAudio()))
    }
}
