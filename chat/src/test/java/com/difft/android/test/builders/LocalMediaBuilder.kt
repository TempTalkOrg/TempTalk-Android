package com.difft.android.test.builders

import com.difft.android.selector.entity.LocalMedia

/**
 * Builders for the three shapes a [LocalMedia] can reach the mediasend chain in.
 *
 * Lives in `:chat` test sources, not `base/src/testFixtures`: `:selector` depends on `:base`, so
 * `:base` cannot see [LocalMedia] at all, and `:chat` is the only consumer.
 *
 * Every factory constructs a fresh instance — never `LocalMedia.obtain()`, which recycles from a
 * pool. Tests assert identity (`===`) on items that must be passed through untouched, so two
 * builder calls must never hand back the same object.
 *
 * [LocalMedia] itself gains no fields and no value-based `hashCode` for these builders: its
 * identity `hashCode` is load-bearing for the identity-keyed maps in the send path.
 */
object LocalMediaBuilder {

    /**
     * Gallery / camera-cell shape (API 29+): a MediaStore content URI in `path`, the (possibly
     * unreadable under scoped storage) bare path in `realPath`.
     */
    @Suppress("LongParameterList")
    fun gallery(
        id: Long = 12345L,
        mime: String = "image/jpeg",
        contentUri: String = "content://media/external/images/media/$id",
        realPath: String = "/storage/emulated/0/DCIM/Camera/img_$id.jpg",
        size: Long = 1_500_000L,
        durationMs: Long = 0L,
    ): LocalMedia = LocalMedia(
        id = id,
        path = contentUri,
        realPath = realPath,
        mimeType = mime,
        size = size,
        duration = durationMs,
        fileName = realPath.substringAfterLast('/'),
    )

    /**
     * SAF / file-attachment shape: `path` empty, only a sandbox absolute path. This is the shape
     * whose immunity to scoped storage must not be broken by the URI migration.
     */
    fun sandbox(
        realPath: String = "/data/user/0/com.difft.android/files/attachment/x.jpg",
        mime: String = "image/jpeg",
    ): LocalMedia = LocalMedia(
        path = "",
        realPath = realPath,
        mimeType = mime,
        fileName = realPath.substringAfterLast('/'),
    )

    /** API 26-28 shape: `path` holds a bare absolute path rather than a content URI. */
    fun legacyBarePath(
        barePath: String = "/storage/emulated/0/DCIM/a.jpg",
        mime: String = "image/jpeg",
    ): LocalMedia = LocalMedia(
        path = barePath,
        realPath = barePath,
        mimeType = mime,
        fileName = barePath.substringAfterLast('/'),
    )
}
