package com.difft.android.chat.util

object YouTubeUtil {
    // Regex, die verschiedene YouTube-Link-Formate erkennt (inkl. Shorts)
    private val YOUTUBE_REGEX = "(?<=watch\\?v=|/videos/|embed/|youtu.be/|shorts/)[^#&?]*".toRegex()

    fun extractVideoId(text: String): String? {
        return YOUTUBE_REGEX.find(text)?.value
    }

    fun containsYouTubeLink(text: String): Boolean {
        return extractVideoId(text) != null
    }
}
