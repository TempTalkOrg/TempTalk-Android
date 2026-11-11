package com.difft.android.base.utils

import com.difft.android.base.utils.globalServices

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object TextSizeUtil {

    const val TEXT_SIZE_DEFAULT = 0
    const val TEXT_SIZE_LAGER = 1

    
    private val _textSizeState = MutableStateFlow(TEXT_SIZE_DEFAULT)

    




    val textSizeState: StateFlow<Int> = _textSizeState.asStateFlow()

    



    val currentTextSize: Int
        get() = _textSizeState.value

    



    val isLarger: Boolean
        get() = _textSizeState.value == TEXT_SIZE_LAGER

    




    fun loadAndEmitTextSize() {
        appScope.launch(Dispatchers.IO) {
            try {
                val textSize = globalServices.userManager.getUserData()?.textSize ?: TEXT_SIZE_DEFAULT
                _textSizeState.value = textSize
            } catch (e: Exception) {
                
                L.w { "[TextSizeUtil] error: ${e.stackTraceToString()}" }
                _textSizeState.value = TEXT_SIZE_DEFAULT
            }
        }
    }

    


    fun updateTextSize(textSize: Int) {
        globalServices.userManager.update {
            this.textSize = textSize
        }
        _textSizeState.value = textSize
    }
}

