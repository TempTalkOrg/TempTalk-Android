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

/**
 * [PermissionConfig.getReadPermissionArray] must not request WRITE_EXTERNAL_STORAGE on
 * API 29-32, where the manifest caps it at maxSdkVersion="28" so it can never be granted
 * (issue #1101). API <= 28 keeps the historic [READ, WRITE] pair, and every SDK level keeps
 * READ_EXTERNAL_STORAGE first because PictureSelectorFragment identifies the camera flow with
 * permissions[0] == PermissionConfig.CAMERA[0].
 *
 * Class-level @Config matches the sibling [PermissionCheckerTest]; per-test @Config narrows
 * the runtime SDK where a specific boundary is under test.
 *
 * Covers T10, T11, T74, T75.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PermissionConfigTest {

    private val app get() = RuntimeEnvironment.getApplication()

    // T10 — API 29/30/32: READ only, WRITE is never requested.
    @Test
    @Config(sdk = [29, 30, 32])
    fun `read permission array on api 29 to 32 asks for read only`() {
        // Guards against a dropped @Config silently running this at API 28 and passing nothing.
        assertTrue(Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2)

        val result = PermissionConfig.getReadPermissionArray(app, SelectMimeType.ofAll())

        assertContentEquals(arrayOf(PermissionConfig.READ_EXTERNAL_STORAGE), result)
        assertFalse(result.contains(PermissionConfig.WRITE_EXTERNAL_STORAGE))
    }

    // T11 — API 28: historic [READ, WRITE] pair preserved, READ still first.
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

    // T74 — minSdk boundary: the SDK split must not reach below API 29.
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

    // T75 — the API 29 fix is not specialised per chooseMode (second consumer: requestSelectMore).
    @Test
    fun `read permission array on api 29 is the same for every choose mode`() {
        val expected = arrayOf(PermissionConfig.READ_EXTERNAL_STORAGE)

        assertContentEquals(expected, PermissionConfig.getReadPermissionArray(app, SelectMimeType.ofImage()))
        assertContentEquals(expected, PermissionConfig.getReadPermissionArray(app, SelectMimeType.ofVideo()))
        assertContentEquals(expected, PermissionConfig.getReadPermissionArray(app, SelectMimeType.ofAudio()))
    }
}
