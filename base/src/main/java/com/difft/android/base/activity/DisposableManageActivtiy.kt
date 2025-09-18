package com.difft.android.base.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.difft.android.base.contracts.DisposableManager
import com.difft.android.base.contracts.SimpleDisposableManager
import io.reactivex.rxjava3.disposables.Disposable

open class DisposableManageActivity : AppCompatActivity(), DisposableManager {
    private lateinit var disposableManager: DisposableManager

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
}