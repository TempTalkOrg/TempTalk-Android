package com.difft.android.selector.config

object SelectMimeType {

    
    @JvmStatic
    fun ofAll(): Int = TYPE_ALL

    
    @JvmStatic
    fun ofImage(): Int = TYPE_IMAGE

    
    @JvmStatic
    fun ofVideo(): Int = TYPE_VIDEO

    


    @JvmStatic
    fun ofAudio(): Int = TYPE_AUDIO

    const val TYPE_ALL = 0
    const val TYPE_IMAGE = 1
    const val TYPE_VIDEO = 2
    const val TYPE_AUDIO = 3
}
