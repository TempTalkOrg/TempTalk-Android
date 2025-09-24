package com.difft.android.login

import android.os.Bundle
import android.view.View
import com.difft.android.base.BaseActivity
import com.difft.android.login.databinding.LoginActivityLoginBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class LoginActivity : BaseActivity() {

    private val mBinding: LoginActivityLoginBinding by viewbind()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding.fragmentLogIn.visibility = View.GONE
        mBinding.fragmentSignUp.visibility = View.VISIBLE
    }
}