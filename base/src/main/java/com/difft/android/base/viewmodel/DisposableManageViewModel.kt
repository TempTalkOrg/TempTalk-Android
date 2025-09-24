package com.difft.android.base.viewmodel

import androidx.lifecycle.ViewModel
import com.difft.android.base.contracts.DisposableManager
import com.difft.android.base.contracts.SimpleDisposableManager

abstract class DisposableManageViewModel : ViewModel(), DisposableManager by SimpleDisposableManager() {

    override fun onCleared() {
        disposeAll()

        super.onCleared()
    }
}