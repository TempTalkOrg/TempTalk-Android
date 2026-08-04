package com.difft.android.selector.config

object SelectMimeType {

    /** GET image or video only (excluding audio). */
    @JvmStatic
    fun ofAll(): Int = TYPE_ALL

    /** GET image only. */
    @JvmStatic
    fun ofImage(): Int = TYPE_IMAGE

    /** GET video only. */
    @JvmStatic
    fun ofVideo(): Int = TYPE_VIDEO

    /**
     * GET audio only. Audio-related functions are no longer maintained; still usable
     * but with device-compatibility issues.
     */
    @JvmStatic
    fun ofAudio(): Int = TYPE_AUDIO

    const val TYPE_ALL = 0
    const val TYPE_IMAGE = 1
    const val TYPE_VIDEO = 2
    const val TYPE_AUDIO = 3
}
