package com.difft.android.chat.util

/** Pure decision logic for which MIME types to try when handing a file to an external viewer. */
object FileOpenMime {

    const val TEXT_PLAIN = "text/plain"

    /**
     * Text formats with a real viewer ecosystem (spreadsheets, browsers, JSON tools): their exact
     * MIME goes first and `text/plain` is only the retry.
     */
    private val EXACT_MIME_FIRST_EXTENSIONS = setOf(
        "txt", "csv", "tsv", "json", "xml", "yml", "yaml", "html", "htm", "css"
    )

    /**
     * Plain-text formats with no dedicated ecosystem. Apps that claim their exact MIME (e.g.
     * `text/markdown`) commonly cannot render them and fail silently, so `text/plain` goes first
     * and the exact MIME is only the retry.
     */
    private val PLAIN_TEXT_FIRST_EXTENSIONS = setOf(
        "md", "markdown", "text", "log", "ini", "cfg", "conf", "properties", "toml", "gradle",
        "kts", "kt", "java", "py", "js", "ts", "sh", "sql", "srt", "diff", "patch"
    )

    /** True when [extension] (with or without a leading dot, any case) names plain-text content. */
    fun isTextLike(extension: String?): Boolean {
        val normalized = normalize(extension) ?: return false
        return normalized in EXACT_MIME_FIRST_EXTENSIONS || normalized in PLAIN_TEXT_FIRST_EXTENSIONS
    }

    /**
     * Ordered MIME candidates for ACTION_VIEW. Non-text files are tried exactly once with [mime]
     * (a null [mime] lets the intent resolve the type from the content provider). Text-like files
     * get both [mime] and `text/plain`, with `text/plain` first for [PLAIN_TEXT_FIRST_EXTENSIONS].
     */
    fun candidates(mime: String?, extension: String?): List<String?> {
        val normalized = normalize(extension)
        if (normalized == null || !isTextLike(normalized)) return listOf(mime)
        if (mime == null || mime.equals(TEXT_PLAIN, ignoreCase = true)) return listOf(TEXT_PLAIN)
        return if (normalized in PLAIN_TEXT_FIRST_EXTENSIONS) listOf(TEXT_PLAIN, mime) else listOf(mime, TEXT_PLAIN)
    }

    private fun normalize(extension: String?): String? =
        extension?.removePrefix(".")?.lowercase()?.takeIf { it.isNotEmpty() }
}
