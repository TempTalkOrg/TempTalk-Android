package com.difft.android.setting

/**
 * "Is an Activity resumed right now?" — the visibility signal the exit path branches on.
 *
 * Deliberately NOT `AppForegroundObserver.isForegrounded()`: that wraps `ProcessLifecycleOwner`,
 * which debounces `ON_STOP` by ~700 ms so a configuration change does not read as a background
 * transition. A logout confirmed inside that window would read "foreground", skip the logged-out
 * notification, then silently no-op its background `startActivity` on API 29+ and kill the
 * process — the unexplained process death this seam exists to prevent, at exactly the moment the
 * user has just backgrounded the app.
 *
 * This answers the question the exit path actually asks ("is there a host on screen that
 * `startActivity` can land on"), with no debounce.
 */
fun interface AppVisibility {
    fun hasResumedActivity(): Boolean
}
