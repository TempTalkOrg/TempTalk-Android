package com.difft.android.chat.common.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SearchInputViewModel(application: Application) : AndroidViewModel(application) {
    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input

    fun setInput(input: String?) {
        _input.value = input ?: ""
    }
}
