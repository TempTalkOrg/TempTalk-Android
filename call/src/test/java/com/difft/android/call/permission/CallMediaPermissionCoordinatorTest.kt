package com.difft.android.call.permission

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test





class CallMediaPermissionCoordinatorTest {

    
    private class FakeUserManager(var data: UserData = UserData()) : UserManager {
        var lastCommit: Boolean? = null

        override fun setUserData(userData: UserData, commit: Boolean) {
            data = userData
            lastCommit = commit
        }

        override fun getUserData(): UserData = data
    }

    private val userManager = FakeUserManager()
    private val coordinator = CallMediaPermissionCoordinator(userManager)
    private val activity = mockk<Activity>(relaxed = true)

    @Before
    fun setUp() {
        
        mockkStatic(ContextCompat::class)
        mockkStatic(ActivityCompat::class)
        
        every { ContextCompat.checkSelfPermission(activity, any()) } returns PackageManager.PERMISSION_DENIED
        every { ActivityCompat.shouldShowRequestPermissionRationale(activity, any()) } returns false
    }

    @After
    fun tearDown() = unmockkAll()

    private fun stubPermission(permission: String, granted: Boolean, rationale: Boolean) {
        every { ContextCompat.checkSelfPermission(activity, permission) } returns
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        every { ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) } returns rationale
    }

    
    
    

    @Test
    fun `granted maps to Granted regardless of other inputs`() {
        assertEquals(
            MediaPermissionState.Granted,
            CallMediaPermissionCoordinator.resolveState(granted = true, rationale = true, requestedBefore = true)
        )
        assertEquals(
            MediaPermissionState.Granted,
            CallMediaPermissionCoordinator.resolveState(granted = true, rationale = false, requestedBefore = false)
        )
    }

    @Test
    fun `rationale true maps to Denied - system still shows the dialog`() {
        assertEquals(
            MediaPermissionState.Denied,
            CallMediaPermissionCoordinator.resolveState(granted = false, rationale = true, requestedBefore = true)
        )
        
        assertEquals(
            MediaPermissionState.Denied,
            CallMediaPermissionCoordinator.resolveState(granted = false, rationale = true, requestedBefore = false)
        )
    }

    @Test
    fun `no rationale but requested before maps to PermanentlyDenied`() {
        assertEquals(
            MediaPermissionState.PermanentlyDenied,
            CallMediaPermissionCoordinator.resolveState(granted = false, rationale = false, requestedBefore = true)
        )
    }

    @Test
    fun `never requested and no rationale maps to NotDetermined`() {
        assertEquals(
            MediaPermissionState.NotDetermined,
            CallMediaPermissionCoordinator.resolveState(granted = false, rationale = false, requestedBefore = false)
        )
    }

    
    
    

    @Test
    fun `tap on granted permission proceeds`() {
        stubPermission(Manifest.permission.RECORD_AUDIO, granted = true, rationale = false)
        assertEquals(
            MediaPermissionTapAction.Proceed,
            coordinator.decideTapAction(activity, CallMediaPermission.Microphone)
        )
    }

    @Test
    fun `tap on never-requested permission launches system request`() {
        stubPermission(Manifest.permission.CAMERA, granted = false, rationale = false)
        assertEquals(
            MediaPermissionTapAction.LaunchSystemRequest,
            coordinator.decideTapAction(activity, CallMediaPermission.Camera)
        )
    }

    @Test
    fun `tap while rationale is available re-launches system request`() {
        userManager.data = UserData(callMicPermissionRequested = true)
        stubPermission(Manifest.permission.RECORD_AUDIO, granted = false, rationale = true)
        assertEquals(
            MediaPermissionTapAction.LaunchSystemRequest,
            coordinator.decideTapAction(activity, CallMediaPermission.Microphone)
        )
    }

    @Test
    fun `tap on permanently denied permission shows settings guide`() {
        userManager.data = UserData(callCameraPermissionRequested = true)
        stubPermission(Manifest.permission.CAMERA, granted = false, rationale = false)
        assertEquals(
            MediaPermissionTapAction.ShowSettingsGuide,
            coordinator.decideTapAction(activity, CallMediaPermission.Camera)
        )
    }

    
    
    

    @Test
    fun `launching the system request persists the per-permission flag durably`() {
        coordinator.onSystemRequestLaunched(CallMediaPermission.Microphone)
        assertTrue(userManager.data.callMicPermissionRequested)
        assertFalse(userManager.data.callCameraPermissionRequested)
        
        
        assertEquals(true, userManager.lastCommit)

        coordinator.onSystemRequestLaunched(CallMediaPermission.Camera)
        assertTrue(userManager.data.callCameraPermissionRequested)
        assertEquals(true, userManager.lastCommit)
    }

    
    
    

    @Test
    fun `refresh publishes per-permission states to the flows`() {
        userManager.data = UserData(callMicPermissionRequested = true)
        stubPermission(Manifest.permission.RECORD_AUDIO, granted = false, rationale = false)
        stubPermission(Manifest.permission.CAMERA, granted = true, rationale = false)

        coordinator.refresh(activity)

        assertEquals(MediaPermissionState.PermanentlyDenied, coordinator.micState.value)
        assertEquals(MediaPermissionState.Granted, coordinator.cameraState.value)
    }

    @Test
    fun `refresh after settings grant clears the denied state but never enables anything`() {
        userManager.data = UserData(callMicPermissionRequested = true)
        stubPermission(Manifest.permission.RECORD_AUDIO, granted = false, rationale = false)
        stubPermission(Manifest.permission.CAMERA, granted = false, rationale = false)
        coordinator.refresh(activity)
        assertEquals(MediaPermissionState.PermanentlyDenied, coordinator.micState.value)

        stubPermission(Manifest.permission.RECORD_AUDIO, granted = true, rationale = false)
        coordinator.refresh(activity)
        assertEquals(MediaPermissionState.Granted, coordinator.micState.value)
    }

    
    
    

    @Test
    fun `badge shows only for Denied and PermanentlyDenied`() {
        assertFalse(MediaPermissionState.NotDetermined.showsBadge)
        assertTrue(MediaPermissionState.Denied.showsBadge)
        assertTrue(MediaPermissionState.PermanentlyDenied.showsBadge)
        assertFalse(MediaPermissionState.Granted.showsBadge)
    }
}
