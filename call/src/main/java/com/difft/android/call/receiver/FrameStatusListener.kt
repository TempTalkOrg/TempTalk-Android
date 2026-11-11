package com.difft.android.call.receiver





interface FrameStatusListener {
    
    fun onFrameAvailable()

    
    fun onNoFrameAvailable()

    
    fun onFirstFrameRendered()

    
    fun onDismissPlaceHolderView()
}