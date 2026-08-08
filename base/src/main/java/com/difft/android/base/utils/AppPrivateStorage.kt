package com.difft.android.base.utils

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import java.io.File
import java.io.IOException

/**
 * Decides whether a filesystem path belongs to this app's own private storage.
 *
 * The judgment is purely structural: canonicalize the candidate path AND the app-private roots,
 * then do a boundary-safe prefix comparison. It never inspects existence, length or file type — a
 * path under a private root is private whether or not a file sits there, and a shared-storage path
 * stays shared even when it happens to be readable.
 *
 * This predicate answers "who owns this path", NOT "can this path be read". It must never be used
 * as a readability check: readability can only be established by actually opening the item.
 *
 * Both sides are canonicalized rather than merely absolutized because Android exposes real symlink
 * aliases for these locations (`/data/user/0` -> `/data/data`; `/sdcard`, `/storage/self/primary`
 * and `/storage/emulated/0` alias each other). Candidate paths reach this predicate from several
 * unrelated producers, so an absolutePath-only comparison yields false negatives on the very same
 * directory — and a false negative here means an app-owned temp file is never cleaned up.
 */
object AppPrivateStorage {

    private val roots: List<String> by lazy { canonicalRoots(application) }

    /** True when [path] resolves inside one of this app's private storage roots. */
    fun isAppPrivate(path: String): Boolean = isUnderAnyRoot(path, roots)

    /**
     * App-private roots: the internal data dir plus `Android/data/<pkg>` on every mounted volume.
     *
     * Taking the whole private root set rather than a single subdirectory is deliberate: pre-Q
     * camera temp files live under `getExternalFilesDir(DIRECTORY_PICTURES)`, and those must stay
     * deletable. `getExternalFilesDirs` may report null entries for unmounted volumes.
     */
    @VisibleForTesting
    internal fun canonicalRoots(context: Context): List<String> = buildList {
        canonicalOrNull(context.dataDir)?.let(::add)
        context.getExternalFilesDirs(null).filterNotNull()
            .mapNotNull { it.parentFile }
            .forEach { canonicalOrNull(it)?.let(::add) }
    }.distinct()

    /**
     * Boundary-safe containment test. Fails closed (returns false) when [path] is blank or cannot be
     * canonicalized: the consumer's action is an irreversible delete, so "unknown" must mean "leave
     * it alone".
     *
     * The `root + File.separator` suffix is load-bearing — a bare prefix match would treat
     * `/data/data/<pkg>evil/x.jpg` as living under the root `/data/data/<pkg>`. Canonicalization
     * also folds `..`, so a path that climbs out of a private root is correctly rejected.
     */
    @VisibleForTesting
    internal fun isUnderAnyRoot(path: String, roots: List<String>): Boolean {
        if (path.isBlank() || roots.isEmpty()) return false
        val candidate = canonicalOrNull(File(path)) ?: return false
        return roots.any { candidate == it || candidate.startsWith(it + File.separator) }
    }

    private fun canonicalOrNull(file: File): String? = try {
        file.canonicalPath
    } catch (e: IOException) {
        // Never log the path itself.
        L.w { "[AppPrivateStorage] canonicalize failed: ${e.javaClass.simpleName}" }
        null
    }
}
