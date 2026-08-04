package com.difft.android.selector.permissions

import android.os.Build
import com.difft.android.selector.config.SelectMimeType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate-2 fork fix: [PermissionChecker.isCheckReadStorage] must treat Android 14+
 * "Select photos" partial access (READ_MEDIA_VISUAL_USER_SELECTED granted,
 * IMAGES/VIDEO denied) as readable, so the selector's upfront gate stops
 * re-launching the system re-selection dialog on every gallery open.
 *
 * Uses [RobolectricTestRunner] + [RuntimeEnvironment.getApplication] directly
 * (no androidx.test.core dependency). Robolectric denies all permissions by
 * default; each case grants only what the scenario requires and sets the app's
 * targetSdkVersion to exercise the partial-access guard.
 *
 * Covers T13–T17 (runtime SDK 34, targetSdk 34) and T23 (targetSdk<34 guard).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionCheckerTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun setTargetSdk(version: Int) {
        app.applicationInfo.targetSdkVersion = version
    }

    private fun grant(vararg permissions: String) {
        shadowOf(app).grantPermissions(*permissions)
    }

    // T13 — partial access, ofAll(): visual granted, images/video denied -> readable (no re-prompt).
    @Test
    fun `partial access on 34 with ofAll is readable`() {
        setTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        grant(PermissionConfig.READ_MEDIA_VISUAL_USER_SELECTED)

        assertTrue(PermissionChecker.isCheckReadStorage(SelectMimeType.ofAll(), app))
    }

    // T14 — partial access, ofImage(): avatar entry path also readable.
    @Test
    fun `partial access on 34 with ofImage is readable`() {
        setTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        grant(PermissionConfig.READ_MEDIA_VISUAL_USER_SELECTED)

        assertTrue(PermissionChecker.isCheckReadStorage(SelectMimeType.ofImage(), app))
    }

    // T15 — true denial: nothing granted -> not readable (initial request dialog preserved).
    @Test
    fun `true denial on 34 with ofAll is not readable`() {
        setTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        // No permissions granted.

        assertFalse(PermissionChecker.isCheckReadStorage(SelectMimeType.ofAll(), app))
    }

    // T16 — full access: images+video granted -> readable (no regression).
    @Test
    fun `full access on 34 with ofAll is readable`() {
        setTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        grant(PermissionConfig.READ_MEDIA_IMAGES, PermissionConfig.READ_MEDIA_VIDEO)

        assertTrue(PermissionChecker.isCheckReadStorage(SelectMimeType.ofAll(), app))
    }

    // T17 — audio excluded: visual granted but audio denied -> not readable for ofAudio().
    @Test
    fun `partial visual grant does not falsely allow ofAudio on 34`() {
        setTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        grant(PermissionConfig.READ_MEDIA_VISUAL_USER_SELECTED)

        assertFalse(PermissionChecker.isCheckReadStorage(SelectMimeType.ofAudio(), app))
    }

    // T23 — targetSdk guard: runtime 34 but targetSdk<34 -> partial exemption skipped,
    // falls back to native IMAGES check (denied) -> not readable.
    @Test
    fun `partial exemption is skipped when targetSdk below 34`() {
        setTargetSdk(Build.VERSION_CODES.TIRAMISU) // 33 < 34
        grant(PermissionConfig.READ_MEDIA_VISUAL_USER_SELECTED)

        assertFalse(PermissionChecker.isCheckReadStorage(SelectMimeType.ofImage(), app))
    }
}
