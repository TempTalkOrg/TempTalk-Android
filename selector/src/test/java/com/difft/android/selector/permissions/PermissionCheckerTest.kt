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

    
    @Test
    fun `partial access on 34 with ofAll is readable`() {
        setTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        grant(PermissionConfig.READ_MEDIA_VISUAL_USER_SELECTED)

        assertTrue(PermissionChecker.isCheckReadStorage(SelectMimeType.ofAll(), app))
    }

    
    @Test
    fun `partial access on 34 with ofImage is readable`() {
        setTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        grant(PermissionConfig.READ_MEDIA_VISUAL_USER_SELECTED)

        assertTrue(PermissionChecker.isCheckReadStorage(SelectMimeType.ofImage(), app))
    }

    
    @Test
    fun `true denial on 34 with ofAll is not readable`() {
        setTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        

        assertFalse(PermissionChecker.isCheckReadStorage(SelectMimeType.ofAll(), app))
    }

    
    @Test
    fun `full access on 34 with ofAll is readable`() {
        setTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        grant(PermissionConfig.READ_MEDIA_IMAGES, PermissionConfig.READ_MEDIA_VIDEO)

        assertTrue(PermissionChecker.isCheckReadStorage(SelectMimeType.ofAll(), app))
    }

    
    @Test
    fun `partial visual grant does not falsely allow ofAudio on 34`() {
        setTargetSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        grant(PermissionConfig.READ_MEDIA_VISUAL_USER_SELECTED)

        assertFalse(PermissionChecker.isCheckReadStorage(SelectMimeType.ofAudio(), app))
    }

    
    
    @Test
    fun `partial exemption is skipped when targetSdk below 34`() {
        setTargetSdk(Build.VERSION_CODES.TIRAMISU) 
        grant(PermissionConfig.READ_MEDIA_VISUAL_USER_SELECTED)

        assertFalse(PermissionChecker.isCheckReadStorage(SelectMimeType.ofImage(), app))
    }
}
