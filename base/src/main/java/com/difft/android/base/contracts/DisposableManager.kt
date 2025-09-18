package com.difft.android.base.contracts

import io.reactivex.rxjava3.disposables.Disposable

interface DisposableManager {
    fun manageDisposable(disposable: Disposable)

    fun ignoreDisposable(disposable: Disposable)

    fun disposeManagedDisposable(disposable: Disposable)

    fun disposeAll()
}