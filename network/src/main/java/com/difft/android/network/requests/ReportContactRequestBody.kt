package com.difft.android.network.requests

data class ReportContactRequestBody(
    val uid: String,
    val type: Int?,
    val reason: String?,
    val block: Int?
)