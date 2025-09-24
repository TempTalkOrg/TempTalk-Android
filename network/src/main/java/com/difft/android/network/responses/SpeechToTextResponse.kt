package com.difft.android.network.responses


data class SpeechToTextResponse(
    val segments: List<Data>?,
    val language: String? = null
)

data class Data(
    val text: String,
    val start: Double,
    val end: Double,
)
