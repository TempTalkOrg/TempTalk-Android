package com.difft.android.base.common

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screenshot detector with compatibility for all Android versions.
 *
 * - Android 14+ (API 34): Uses official ScreenCaptureCallback API
 * - Android 14 below: Uses ContentObserver to monitor MediaStore changes
 *
 * Usage:
 * ```
 * val detector = ScreenshotDetector(
 *     activity = requireActivity(),
 *     coroutineScope = viewLifecycleOwner.lifecycleScope,
 *     onScreenshotDetected = { /* Handle screenshot */ }
 * )
 * detector.startListening() // Call in onResume
 * detector.stopListening()  // Call in onPause
 * ```
 *
 * @param activity The activity for registering callbacks and accessing content resolver
 * @param coroutineScope Coroutine scope for async operations (recommend using lifecycleScope)
 * @param onScreenshotDetected Callback when screenshot is detected
 */
class ScreenshotDetector(
    private val activity: Activity,
    private val coroutineScope: CoroutineScope,
    private val onScreenshotDetected: () -> Unit
) {
    companion object {
        private const val TAG = "Screenshot"

        // Debounce time to avoid duplicate callbacks
        private const val DEBOUNCE_MS = 1500L

        // Only consider images added within this time window as screenshots
        private const val TIME_WINDOW_SECONDS = 5L

        // Screenshot folder paths - only path matching is used (more reliable than filename matching)
        // This avoids false positives when saving images with "screenshot" in the filename
        private val SCREENSHOT_PATH_KEYWORDS = listOf(
            "screenshots/",      // Standard Android: DCIM/Screenshots/, Pictures/Screenshots/
            "screenshot/",       // Some devices
            "screencapture/",    // Some devices
            "截屏/",              // Chinese: Screenshots folder
            "截图/",              // Chinese: Screenshots folder
            "miui/screenshot"    // MIUI specific
        )
    }

    private var isListening = false
    private var lastDetectedTime = 0L
    private var startListenTime = 0L

    // Android 14+ official API callback (lazy to avoid ClassNotFoundException on older versions)
    @get:RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private val screenCaptureCallback by lazy {
        Activity.ScreenCaptureCallback {
            L.i { "[$TAG] Screenshot detected via ScreenCaptureCallback (Android 14+)" }
            handleScreenshotDetected()
        }
    }

    // ContentObserver for older Android versions
    private var contentObserver: ContentObserver? = null

    /**
     * Start listening for screenshots.
     * Should be called in Activity's onResume.
     */
    fun startListening() {
        if (isListening) {
            L.d { "[$TAG] Already listening, skipping" }
            return
        }

        isListening = true
        startListenTime = System.currentTimeMillis()
        lastDetectedTime = 0L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startListeningApi34()
        } else {
            startListeningLegacy()
        }

        L.i { "[$TAG] Started listening (API ${Build.VERSION.SDK_INT})" }
    }

    /**
     * Stop listening for screenshots.
     * Should be called in Activity's onPause.
     */
    fun stopListening() {
        if (!isListening) {
            return
        }

        isListening = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            stopListeningApi34()
        } else {
            stopListeningLegacy()
        }

        L.i { "[$TAG] Stopped listening" }
    }

    /**
     * Release resources.
     * Should be called in Activity's onDestroy.
     * Note: Coroutine scope is managed externally (e.g., lifecycleScope auto-cancels on destroy)
     */
    fun release() {
        stopListening()
    }

    // ============ Android 14+ Implementation ============

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startListeningApi34() {
        try {
            activity.registerScreenCaptureCallback(
                activity.mainExecutor,
                screenCaptureCallback
            )
        } catch (e: Exception) {
            L.e { "[$TAG] Failed to register ScreenCaptureCallback: ${e.message}" }
            // Fallback to legacy method
            startListeningLegacy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun stopListeningApi34() {
        try {
            activity.unregisterScreenCaptureCallback(screenCaptureCallback)
        } catch (e: Exception) {
            L.e { "[$TAG] Failed to unregister ScreenCaptureCallback: ${e.message}" }
        }
    }

    // ============ Legacy Implementation (ContentObserver) ============

    private fun startListeningLegacy() {
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri?.let { checkIfScreenshot(it) }
            }
        }

        try {
            activity.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
        } catch (e: Exception) {
            L.e { "[$TAG] Failed to register ContentObserver: ${e.message}" }
        }
    }

    private fun stopListeningLegacy() {
        contentObserver?.let { observer ->
            try {
                activity.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                L.e { "[$TAG] Failed to unregister ContentObserver: ${e.message}" }
            }
        }
        contentObserver = null
    }

    private fun checkIfScreenshot(uri: Uri) {
        // Check permission first
        if (!hasReadPermission()) {
            L.w { "[$TAG] No read permission, cannot check screenshot" }
            return
        }

        coroutineScope.launch {
            // Try with retry mechanism (file might not be indexed immediately)
            var isScreenshot = false
            for (attempt in 1..3) {
                isScreenshot = withContext(Dispatchers.IO) {
                    isScreenshotUri(uri)
                }
                if (isScreenshot) break

                // Wait a bit before retry (file might not be indexed yet)
                if (attempt < 3) {
                    delay(200L * attempt)
                }
            }

            if (isScreenshot) {
                L.i { "[$TAG] Screenshot detected via ContentObserver" }
                handleScreenshotDetected()
            }
        }
    }

    private fun hasReadPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isScreenshotUri(uri: Uri): Boolean {
        return try {
            // For Android 10+, use DISPLAY_NAME and RELATIVE_PATH
            // For older versions, use DATA column
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isScreenshotUriApi29(uri)
            } else {
                isScreenshotUriLegacy(uri)
            }
        } catch (e: Exception) {
            L.e { "[$TAG] Error checking screenshot URI: ${e.message}" }
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isScreenshotUriApi29(uri: Uri): Boolean {
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED
        )

        // Try direct URI query first
        var cursor = activity.contentResolver.query(uri, projection, null, null, null)

        // If direct query fails, try querying by ID from the URI
        if (cursor == null || cursor.count == 0) {
            cursor?.close()
            cursor = null  // Ensure cursor is null before potential reassignment
            val id = uri.lastPathSegment
            if (id != null) {
                L.d { "[$TAG] Direct query failed, trying by ID: $id" }
                cursor = activity.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Images.Media._ID} = ?",
                    arrayOf(id),
                    null
                )
            }
        }

        cursor?.use { c ->
            L.d { "[$TAG] Query result: count=${c.count}" }
            if (c.moveToFirst()) {
                val displayNameIndex = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val relativePathIndex = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                val dateAddedIndex = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)

                val displayName = if (displayNameIndex >= 0) c.getString(displayNameIndex)?.lowercase() ?: "" else ""
                val relativePath = if (relativePathIndex >= 0) c.getString(relativePathIndex)?.lowercase() ?: "" else ""
                val dateAdded = if (dateAddedIndex >= 0) c.getLong(dateAddedIndex) else 0L

                // Check if recently added (within time window)
                val currentTimeSeconds = System.currentTimeMillis() / 1000
                val isRecent = currentTimeSeconds - dateAdded < TIME_WINDOW_SECONDS

                // Check if after we started listening
                val dateAddedMs = dateAdded * 1000
                val isAfterStart = dateAddedMs >= startListenTime

                // Strict check: path MUST contain screenshot folder keyword
                // Filename match alone is not reliable (e.g., saving an image named "screenshot_xxx.jpg" from chat)
                val isScreenshot = SCREENSHOT_PATH_KEYWORDS.any { keyword ->
                    relativePath.contains(keyword)
                }

                L.d { "[$TAG] Check: name=$displayName, path=$relativePath, recent=$isRecent, afterStart=$isAfterStart, isScreenshot=$isScreenshot" }

                return isRecent && isAfterStart && isScreenshot
            }
        }

        L.d { "[$TAG] Query returned no results for uri=$uri" }
        return false
    }

    @Suppress("DEPRECATION")
    private fun isScreenshotUriLegacy(uri: Uri): Boolean {
        val projection = arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED
        )

        activity.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val dateAddedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)

                val data = if (dataIndex >= 0) cursor.getString(dataIndex)?.lowercase() ?: "" else ""
                val dateAdded = if (dateAddedIndex >= 0) cursor.getLong(dateAddedIndex) else 0L

                // Check if recently added
                val currentTimeSeconds = System.currentTimeMillis() / 1000
                val isRecent = currentTimeSeconds - dateAdded < TIME_WINDOW_SECONDS

                // Check if after we started listening
                val dateAddedMs = dateAdded * 1000
                val isAfterStart = dateAddedMs >= startListenTime

                // Strict check: path MUST contain screenshot folder keyword
                // Filename match alone is not reliable (e.g., saving an image named "screenshot_xxx.jpg" from chat)
                val isScreenshot = SCREENSHOT_PATH_KEYWORDS.any { keyword ->
                    data.contains(keyword)
                }

                return isRecent && isAfterStart && isScreenshot
            }
        }
        return false
    }

    // ============ Common ============

    private fun handleScreenshotDetected() {
        // Debounce to avoid duplicate callbacks
        val now = System.currentTimeMillis()
        if (now - lastDetectedTime < DEBOUNCE_MS) {
            L.d { "[$TAG] Debounced screenshot callback" }
            return
        }
        lastDetectedTime = now

        // Use coroutine to ensure callback runs on main thread
        // Dispatchers.Main.immediate executes immediately if already on main thread
        coroutineScope.launch(Dispatchers.Main.immediate) {
            onScreenshotDetected()
        }
    }
}
