package com.difft.android.base.contracts

import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable

class SimpleDisposableManager : DisposableManager {
    private var disposableManager: CompositeDisposable = CompositeDisposable()

    override fun manageDisposable(disposable: Disposable) {
        disposableManager.add(disposable)
    }

    override fun ignoreDisposable(disposable: Disposable) {
        disposableManager.delete(disposable)
    }

    override fun disposeManagedDisposable(disposable: Disposable) {
        disposableManager.remove(disposableManager)
    }

    override fun disposeAll() {
        disposableManager.dispose()
    }
}