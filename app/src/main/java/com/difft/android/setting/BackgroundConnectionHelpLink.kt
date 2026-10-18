package com.difft.android.setting

import android.os.Build
import java.net.URLEncoder
import java.util.Locale

/**
 * Builds the "more guidance" link for the background-connection settings page.
 *
 * The page lives next to the proxy help page and opens the section matching the device brand, so
 * the link carries the manufacturer, model and API level: device data only, no account identifiers.
 * `from` names the settings screen the user came from; the page decides what to do with it, so the
 * client never has to change when the page layout does.
 */
object BackgroundConnectionHelpLink {

    const val BASE_URL = "https://quicall.app/notification-help-android.html"

    enum class Source(val value: String) {
        BACKGROUND_CONNECTION("connection"),
        NOTIFICATION_SETTINGS("notification"),
    }

    fun build(source: Source): String = build(
        language = Locale.getDefault().language,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        source = source,
    )

    fun build(language: String, manufacturer: String, model: String, sdkInt: Int, source: Source): String {
        val lng = if (language.lowercase(Locale.ROOT).startsWith("zh")) "zh" else "en"
        val params = listOf(
            "lng" to lng,
            "brand" to manufacturer.trim().lowercase(Locale.ROOT),
            "model" to model.trim(),
            "sdk" to sdkInt.toString(),
            "from" to source.value,
        ).filter { (_, v) -> v.isNotEmpty() }
        return params.joinToString(prefix = "$BASE_URL?", separator = "&") { (k, v) ->
            "$k=${URLEncoder.encode(v, Charsets.UTF_8.name())}"
        }
    }
}
