package com.difft.android.app

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Signature matchers in [CrashFilter]; the GMS-client stacks mirror Crashlytics issue 8d782ca9, a
 * SecurityException parcelled back from GMS and re-thrown inside play-services' own Handler, with
 * no app frame anywhere on the stack.
 */
class CrashFilterTest {

    @Test
    fun `matches SecurityException raised inside the GMS client with no app frames`() {
        val e = SecurityException("Failed to find provider com.google.android.gsf.gservices for user 0")
            .withStack(PARCEL_FRAME, GMS_CLIENT_FRAME, GMS_HANDLER_FRAME, HANDLER_FRAME, LOOPER_FRAME)

        assertTrue(CrashFilter.isGmsClientSecurityException(e))
    }

    @Test
    fun `does not match when an app frame is on the stack`() {
        val e = SecurityException("Failed to find provider com.google.android.gsf.gservices for user 0")
            .withStack(PARCEL_FRAME, GMS_CLIENT_FRAME, APP_FRAME, HANDLER_FRAME, LOOPER_FRAME)

        assertFalse(CrashFilter.isGmsClientSecurityException(e))
    }

    @Test
    fun `does not match a SecurityException from outside the GMS client`() {
        val e = SecurityException("Permission Denial: opening provider")
            .withStack(PARCEL_FRAME, HANDLER_FRAME, LOOPER_FRAME)

        assertFalse(CrashFilter.isGmsClientSecurityException(e))
    }

    @Test
    fun `does not match an in-process SecurityException that merely passes through GMS client frames`() {
        val e = SecurityException("Permission Denial: requires android.permission.READ_PHONE_STATE")
            .withStack(ENFORCE_PERMISSION_FRAME, GMS_CLIENT_FRAME, GMS_HANDLER_FRAME, HANDLER_FRAME, LOOPER_FRAME)

        assertFalse(CrashFilter.isGmsClientSecurityException(e))
    }

    @Test
    fun `does not match other exception types even with GMS client frames`() {
        val e = IllegalStateException("boom")
            .withStack(PARCEL_FRAME, GMS_CLIENT_FRAME, HANDLER_FRAME, LOOPER_FRAME)

        assertFalse(CrashFilter.isGmsClientSecurityException(e))
    }

    // ---- missing native library on a base-only install (Crashlytics issue 71770801) ----

    @Test
    fun `matches UnsatisfiedLinkError for a missing library on a base-only install`() {
        val e = UnsatisfiedLinkError(DLOPEN_NOT_FOUND)

        assertTrue(CrashFilter.isMissingNativeLibraryCrash(e, baseOnlyInstall = true))
    }

    @Test
    fun `matches NoClassDefFoundError wrapping the UnsatisfiedLinkError`() {
        val e = NoClassDefFoundError("com.tencent.wcdb.core.Database").initCause(UnsatisfiedLinkError(DLOPEN_NOT_FOUND))

        assertTrue(CrashFilter.isMissingNativeLibraryCrash(e, baseOnlyInstall = true))
    }

    @Test
    fun `does not match when the install has its splits`() {
        val e = UnsatisfiedLinkError(DLOPEN_NOT_FOUND)

        assertFalse(CrashFilter.isMissingNativeLibraryCrash(e, baseOnlyInstall = false))
    }

    @Test
    fun `does not match an UnsatisfiedLinkError that is not a missing library`() {
        val e = UnsatisfiedLinkError("No implementation found for long com.tencent.wcdb.core.Database.open(java.lang.String)")

        assertFalse(CrashFilter.isMissingNativeLibraryCrash(e, baseOnlyInstall = true))
    }

    @Test
    fun `does not match unrelated errors on a base-only install`() {
        val e = NoClassDefFoundError("com.example.Missing").initCause(ClassNotFoundException("com.example.Missing"))

        assertFalse(CrashFilter.isMissingNativeLibraryCrash(e, baseOnlyInstall = true))
    }

    // ---- framework API missing on a ROM that misreports its SDK level (Crashlytics 41c8b4ad / 85d9ee38) ----

    @Test
    fun `matches a listed framework member missing on a device that reports its API level`() {
        val e = NoSuchMethodError(SYSTEM_OVERLAYS_MISSING).withStack(OUTLINE_FRAME, INSETS_COMPAT_FRAME, APP_FRAME)

        assertTrue(CrashFilter.isSpoofedSdkFrameworkMiss(e, sdkInt = API_34))
        assertTrue(CrashFilter.isSpoofedSdkFrameworkMiss(e, sdkInt = API_34 + 2))
    }

    @Test
    fun `matches when the outline sits in a library fork class`() {
        val e = NoSuchMethodError(FULL_SCREEN_INTENT_MISSING).withStack(LIBRARY_OUTLINE_FRAME, APP_FRAME)

        assertTrue(CrashFilter.isSpoofedSdkFrameworkMiss(e, sdkInt = API_34))
    }

    @Test
    fun `matches a NoSuchMethodError wrapped in a cause chain`() {
        val cause = NoSuchMethodError(SYSTEM_OVERLAYS_MISSING).withStack(OUTLINE_FRAME, APP_FRAME)
        val e = ExceptionInInitializerError(cause).withStack(APP_FRAME, HANDLER_FRAME)

        assertTrue(CrashFilter.isSpoofedSdkFrameworkMiss(e, sdkInt = API_34))
    }

    @Test
    fun `does not match on a device below the member's API level`() {
        // An unguarded call on a genuine older device misses the same member and must keep reporting.
        val e = NoSuchMethodError(FULL_SCREEN_INTENT_MISSING).withStack(LIBRARY_OUTLINE_FRAME, APP_FRAME)

        assertFalse(CrashFilter.isSpoofedSdkFrameworkMiss(e, sdkInt = API_34 - 1))
    }

    @Test
    fun `does not match a framework member outside the signature table`() {
        val e = NoSuchMethodError("No virtual method getFoo()I in class Landroid/app/NotificationManager; or its super classes")
            .withStack(OUTLINE_FRAME, APP_FRAME)

        assertFalse(CrashFilter.isSpoofedSdkFrameworkMiss(e, sdkInt = API_34))
    }

    @Test
    fun `does not match without an outline frame`() {
        val e = NoSuchMethodError(FULL_SCREEN_INTENT_MISSING).withStack(APP_FRAME, HANDLER_FRAME)

        assertFalse(CrashFilter.isSpoofedSdkFrameworkMiss(e, sdkInt = API_34))
    }

    @Test
    fun `does not match a missing member of a bundled library`() {
        val e = NoSuchMethodError("No virtual method canUseFullScreenIntent()Z in class Lcom/example/lib/Widget; or its super classes")
            .withStack(OUTLINE_FRAME, APP_FRAME)

        assertFalse(CrashFilter.isSpoofedSdkFrameworkMiss(e, sdkInt = API_34))
    }

    @Test
    fun `does not match other error types through an outline`() {
        val e = NoClassDefFoundError("android.view.WindowInsets\$Type").withStack(OUTLINE_FRAME, APP_FRAME)

        assertFalse(CrashFilter.isSpoofedSdkFrameworkMiss(e, sdkInt = API_34))
    }

    private fun <T : Throwable> T.withStack(vararg frames: StackTraceElement): T = apply { stackTrace = arrayOf(*frames) }

    private companion object {
        val PARCEL_FRAME = StackTraceElement("android.os.Parcel", "createExceptionOrNull", "Parcel.java", 2465)
        val GMS_CLIENT_FRAME =
            StackTraceElement("com.google.android.gms.common.internal.BaseGmsClient", "getRemoteService", "BaseGmsClient.java", 17)
        val GMS_HANDLER_FRAME = StackTraceElement("com.google.android.gms.common.internal.zzb", "handleMessage", "zzb.java", 33)
        val APP_FRAME = StackTraceElement("com.difft.android.push.PushUtil", "initFCMPush", "PushUtil.kt", 90)
        val ENFORCE_PERMISSION_FRAME = StackTraceElement("android.app.ContextImpl", "enforce", "ContextImpl.java", 2400)
        val HANDLER_FRAME = StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 117)
        val LOOPER_FRAME = StackTraceElement("android.os.Looper", "loop", "Looper.java", 293)
        const val DLOPEN_NOT_FOUND = "dlopen failed: library \"libWCDB.so\" not found"
        val OUTLINE_FRAME =
            StackTraceElement("androidx.core.view.WindowCompat\$\$ExternalSyntheticApiModelOutline0", "m\$8", "D8\$\$SyntheticClass", 0)
        val LIBRARY_OUTLINE_FRAME =
            StackTraceElement("timber.log.BaseTree\$\$ExternalSyntheticApiModelOutline0", "m", "D8\$\$SyntheticClass", 0)
        const val API_34 = 34
        val INSETS_COMPAT_FRAME =
            StackTraceElement("androidx.core.view.WindowInsetsCompat\$TypeImpl34", "toPlatformType", "WindowInsetsCompat.java", 2880)
        const val SYSTEM_OVERLAYS_MISSING =
            "No static method systemOverlays()I in class Landroid/view/WindowInsets\$Type; or its super classes " +
                "(declaration of 'android.view.WindowInsets\$Type' appears in /system/framework/framework.jar!classes3.dex)"
        const val FULL_SCREEN_INTENT_MISSING =
            "No virtual method canUseFullScreenIntent()Z in class Landroid/app/NotificationManager; or its super classes " +
                "(declaration of 'android.app.NotificationManager' appears in /system/framework/framework.jar)"
    }
}
