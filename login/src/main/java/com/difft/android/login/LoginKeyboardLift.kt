package com.difft.android.login

import android.view.View
import android.view.ViewGroup
import com.difft.android.base.utils.liftAboveKeyboard
import com.difft.android.login.databinding.ActivityBindAccountBinding
import com.difft.android.login.databinding.ActivityVerifyCodeBinding










internal fun ViewGroup.liftLoginFormAboveKeyboard(loginPage: View) = liftAboveKeyboard(
    field = { loginPage.findViewById(R.id.cl_account) },
    anchor = { loginPage.findViewById(R.id.btn_next) },
)


internal fun ActivityBindAccountBinding.liftFormAboveKeyboard() =
    root.liftAboveKeyboard(field = { clAccount }, anchor = { btnNext })


internal fun ActivityVerifyCodeBinding.liftCodeAboveKeyboard() =
    root.liftAboveKeyboard(field = { inputZone }, anchor = { btnNext })
