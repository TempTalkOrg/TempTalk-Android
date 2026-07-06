package com.difft.android.network.responses

/**
 * GIF trending/search response payload (inside the BaseResponse envelope's `data` field).
 * Ported from difft-android; structure is the cross-platform contract (GIPHY proxied
 * through the server `/gifs/` endpoint). See cross-platform-alignment.md §1.
 *
 * - `original` / `preview` carry both `gif` and `webp` URLs: display uses `webp`,
 *   send uses `gif` (animated on legacy/cross-platform receivers).
 * - Pagination uses dual cursors: `pagination` (count/offset/total_count) and `next`.
 */
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
