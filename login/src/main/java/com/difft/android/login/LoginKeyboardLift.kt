package com.difft.android.login

import android.view.View
import android.view.ViewGroup
import com.difft.android.base.utils.liftAboveKeyboard
import com.difft.android.login.databinding.ActivityBindAccountBinding
import com.difft.android.login.databinding.ActivityVerifyCodeBinding

// The sign-in forms are edge-to-edge, so adjustResize does not resize them: on a short window
// (a folded outer screen) the keyboard covers the input and its button. Each form lifts its
// page root just enough to keep the button above the keyboard, never moving the input under the
// status bar (see liftAboveKeyboard).

/**
 * Login page: [loginPage] is the login fragment's container. While sign-up is shown that
 * container is gone, so its button is not shown and nothing lifts.
 */
internal fun ViewGroup.liftLoginFormAboveKeyboard(loginPage: View) = liftAboveKeyboard(
    field = { loginPage.findViewById(R.id.cl_account) },
    anchor = { loginPage.findViewById(R.id.btn_next) },
)

/** Bind / change account: the bordered account row and Next. */
internal fun ActivityBindAccountBinding.liftFormAboveKeyboard() =
    root.liftAboveKeyboard(field = { clAccount }, anchor = { btnNext })

/** Verification code: the code boxes (with their error line) and Next. */
internal fun ActivityVerifyCodeBinding.liftCodeAboveKeyboard() =
    root.liftAboveKeyboard(field = { inputZone }, anchor = { btnNext })
