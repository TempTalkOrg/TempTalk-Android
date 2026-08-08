package com.difft.android.chat.common

import android.content.Context
import android.os.Environment
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.entity.LocalMedia
import java.io.File

/**
 * Cleans up the plaintext image artifacts that PictureSelector's pick → crop → compress pipeline
 * leaves on the app-private external storage while picking an avatar. Two locations are involved:
 *
 * - UCrop output: `Android/data/<pkg>/files/Pictures/CROP_*.jpg` (see
 *   [com.difft.android.selector.utils.FileDirMap]).
 * - Luban compression output: files under `Android/data/<pkg>/cache/luban_disk_cache/` (Luban's
 *   default disk cache under `Context.getExternalCacheDir()`; only created for sources larger than
 *   the `ignoreBy(100)` threshold in `ImageFileCompressEngine`).
 *
 * Avatar flows (personal / group / contact remark) always upload and preview via
 * `LocalMedia.compressPath ?: realPath` — the crop output (`cutPath`/`sandboxPath`) is never read
 * again once picking finishes, so it is removed immediately ([deleteCropTemp]). The compressed copy
 * is the upload/preview source during the flow, so it is removed on the upload-success path
 * ([deleteUploadedTemp]). [sweepPickTempDirs] is a cold-start backstop that mops up anything left
 * behind by a crash mid-flow or an older build.
 */
object AvatarPickTempCleaner {

    /** Prefix UCrop uses for its crop output filename (see `PictureCommonFragment.onCrop`). */
    private const val CROP_FILE_PREFIX = "CROP_"

    /** Luban's default disk-cache dir name under `Context.getExternalCacheDir()`. */
    private const val LUBAN_CACHE_DIR = "luban_disk_cache"

    /**
     * Delete the crop output produced for a picked [media].
     *
     * Targets only the UCrop output under `files/Pictures/` (`cutPath` and its mirror
     * `sandboxPath`); never the original gallery image (`realPath`) nor the compressed copy
     * still used for the on-screen preview/upload ([keepPath]). Safe to call from a UI callback:
     * each deletion is a single stat + unlink.
     */
    fun deleteCropTemp(media: LocalMedia?, keepPath: String? = null) {
        media ?: return
        setOfNotNull(media.cutPath, media.sandboxPath)
            .filter { it.isNotEmpty() && it != keepPath }
            .forEach { path ->
                runCatching {
                    val file = File(path)
                    if (file.isFile) file.delete()
                }.onFailure { L.w { "[AvatarPickTempCleaner] delete crop temp failed: $path, ${it.message}" } }
            }
    }

    /**
     * Delete the file that was just uploaded as an avatar, but ONLY when it lives inside one of the
     * pick temp dirs (`files/Pictures/` or `cache/luban_disk_cache/`).
     *
     * This removes the plaintext compressed/crop copy that served as the upload source, in the same
     * session — without touching a picked gallery original (`realPath`, outside these dirs), a
     * generated random-avatar file (written elsewhere), or any unrelated path. Call from the
     * upload-success path, after any transient on-screen preview of [path] has already been loaded.
     */
    fun deleteUploadedTemp(context: Context, path: String?) {
        if (path.isNullOrEmpty()) return
        runCatching {
            val file = File(path)
            if (file.isFile && isInsidePickTempDir(context, file)) file.delete()
        }.onFailure { L.w { "[AvatarPickTempCleaner] delete uploaded temp failed: $path, ${it.message}" } }
    }

    private fun isInsidePickTempDir(context: Context, file: File): Boolean {
        val target = runCatching { file.canonicalPath }.getOrElse { return false }
        val roots = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            context.externalCacheDir?.let { File(it, LUBAN_CACHE_DIR) },
        )
        return roots.any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            target == rootPath || target.startsWith(rootPath + File.separator)
        }
    }

    /**
     * Cold-start fallback sweep of the plaintext pick temp files. Safe to run unconditionally at
     * process start — no pick/upload can be in flight, so nothing being deleted is still in use.
     *
     * - `files/Pictures/`: only files matching [CROP_FILE_PREFIX] are removed — deliberately
     *   narrower than the library's blanket dir cleaner so it can never touch anything else that
     *   might land there (e.g. a saved-image download fallback).
     * - `cache/luban_disk_cache/`: the whole dir is cleared; it is exclusively Luban's transient
     *   compression cache, so every file in it is a disposable plaintext temp.
     *
     * Only top-level plain files are considered in each dir.
     */
    fun sweepPickTempDirs(context: Context) {
        runCatching {
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?.listFiles { file -> file.isFile && file.name.startsWith(CROP_FILE_PREFIX) }
                ?.forEach { it.delete() }
        }.onFailure { L.w { "[AvatarPickTempCleaner] sweep crop dir failed: ${it.message}" } }

        runCatching {
            context.externalCacheDir
                ?.let { File(it, LUBAN_CACHE_DIR) }
                ?.takeIf { it.isDirectory }
                ?.listFiles { file -> file.isFile }
                ?.forEach { it.delete() }
        }.onFailure { L.w { "[AvatarPickTempCleaner] sweep luban dir failed: ${it.message}" } }
    }
}
