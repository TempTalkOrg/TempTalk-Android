package com.difft.android.network.requests

data class CriticalAlertRequestBodyNew(
    val destinations: List<CriticalAlertDestination>? = null,
    val group: CriticalAlertGroup? = null,
    val roomId: String
)

data class CriticalAlertDestination(
    val number: String,
    val timestamp: Long
)

data class CriticalAlertGroup(
    val gid: String,
    val timestamp: Long
)