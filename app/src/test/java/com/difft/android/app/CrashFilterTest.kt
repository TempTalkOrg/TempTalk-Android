package com.difft.android.app

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Signature matcher for the GMS-client SecurityException rescue in [CrashFilter]. The stacks mirror
 * Crashlytics issue 8d782ca9: a SecurityException parcelled back from GMS and re-thrown inside
 * play-services' own Handler, with no app frame anywhere on the stack.
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
    }
}
