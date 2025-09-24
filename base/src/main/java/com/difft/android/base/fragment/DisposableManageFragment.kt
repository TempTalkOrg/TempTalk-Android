package com.difft.android.base.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.difft.android.base.contracts.DisposableManager
import com.difft.android.base.contracts.SimpleDisposableManager
import io.reactivex.rxjava3.disposables.Disposable

open class DisposableManageFragment : Fragment(), DisposableManager {
    private lateinit var disposableManager: DisposableManager
    private lateinit var viewDisposableManager: DisposableManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        disposableManager = SimpleDisposableManager()
    }

    override fun onDestroy() {
        disposeAll()

        super.onDestroy()
    }

    override fun manageDisposable(disposable: Disposable) {
        disposableManager.manageDisposable(disposable)
    }

    override fun ignoreDisposable(disposable: Disposable) {
        disposableManager.ignoreDisposable(disposable)
    }

    override fun disposeManagedDisposable(disposable: Disposable) {
        disposableManager.disposeManagedDisposable(disposable)
    }

    override fun disposeAll() {
        disposableManager.disposeAll()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewDisposableManager = SimpleDisposableManager()

        super.onViewCreated(view, savedInstanceState)
    }

    override fun onDestroyView() {
        disposeAllViewDisposables()

        super.onDestroyView()
    }

    protected fun manageViewDisposable(disposable: Disposable) {
        viewDisposableManager.manageDisposable(disposable)
    }

    protected fun ignoreViewDisposable(disposable: Disposable) {
        viewDisposableManager.ignoreDisposable(disposable)
    }

    protected fun disposeManagedViewDisposable(disposable: Disposable) {
        viewDisposableManager.disposeManagedDisposable(disposable)
    }

    protected fun disposeAllViewDisposables() {
        viewDisposableManager.disposeAll()
    }
}