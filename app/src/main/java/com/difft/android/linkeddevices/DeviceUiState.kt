package com.difft.android.linkeddevices

/**
 * UI model for one secondary device row. [created]/[lastActive] are epoch milliseconds; the
 * "Unnamed Device" fallback and date formatting stay in the Composable to keep the ViewModel
 * Context-free.
 */
data class DeviceUiState(
    val id: Int,
    val rawName: String?,
    val created: Long,
    val lastActive: Long,
    val isUnlinking: Boolean = false,
) {
    /** Non-blank display name, or null when absent (UI falls back to "Unnamed Device"). */
    val displayName: String? get() = rawName?.takeIf { it.isNotBlank() }
}
