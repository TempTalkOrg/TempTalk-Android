package com.difft.android.chat.util


object FileOpenMime {

    const val TEXT_PLAIN = "text/plain"

    



    private val EXACT_MIME_FIRST_EXTENSIONS = setOf(
        "txt", "csv", "tsv", "json", "xml", "yml", "yaml", "html", "htm", "css"
    )

    




    private val PLAIN_TEXT_FIRST_EXTENSIONS = setOf(
        "md", "markdown", "text", "log", "ini", "cfg", "conf", "properties", "toml", "gradle",
        "kts", "kt", "java", "py", "js", "ts", "sh", "sql", "srt", "diff", "patch"
    )

    
    fun isTextLike(extension: String?): Boolean {
        val normalized = normalize(extension) ?: return false
        return normalized in EXACT_MIME_FIRST_EXTENSIONS || normalized in PLAIN_TEXT_FIRST_EXTENSIONS
    }

    




    fun candidates(mime: String?, extension: String?): List<String?> {
        val normalized = normalize(extension)
        if (normalized == null || !isTextLike(normalized)) return listOf(mime)
        if (mime == null || mime.equals(TEXT_PLAIN, ignoreCase = true)) return listOf(TEXT_PLAIN)
        return if (normalized in PLAIN_TEXT_FIRST_EXTENSIONS) listOf(TEXT_PLAIN, mime) else listOf(mime, TEXT_PLAIN)
    }

    private fun normalize(extension: String?): String? =
        extension?.removePrefix(".")?.lowercase()?.takeIf { it.isNotEmpty() }
}
