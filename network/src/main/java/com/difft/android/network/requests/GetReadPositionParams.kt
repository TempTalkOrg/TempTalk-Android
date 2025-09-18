package com.difft.android.network.requests


data class GetReadPositionParams(
    val gid: String?,
    val number: String?,
    val minServerTimestamp: Long?,
    val maxServerTimestamp: Long?,
    val self: Boolean,
    val page: Int?,
)