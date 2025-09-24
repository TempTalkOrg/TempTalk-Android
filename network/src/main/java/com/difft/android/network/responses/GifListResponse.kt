package com.difft.android.network.responses

data class GifListResponse(
    val `data`: List<GifData>?,
    val next: String?,
    val pagination: Pagenation?
)

data class GifData(
    val id: String?,
    val original: GifDetailData?,
    val preview: GifDetailData?,
    val title: String?
)

data class Pagenation(
    val count: Int,
    val offset: Int,
    val total_count: Int
)

data class GifDetailData(
    val gif: String?,
    val height: Int,
    val webp: String?,
    val width: Int
)