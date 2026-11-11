package com.difft.android.linkeddevices






data class DeviceUiState(
    val id: Int,
    val rawName: String?,
    val created: Long,
    val lastActive: Long,
    val isUnlinking: Boolean = false,
) {
    
    val displayName: String? get() = rawName?.takeIf { it.isNotBlank() }
}
