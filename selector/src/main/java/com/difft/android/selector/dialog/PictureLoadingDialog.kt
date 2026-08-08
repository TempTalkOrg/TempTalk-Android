package com.difft.android.selector.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import com.difft.android.selector.R

class PictureLoadingDialog(context: Context) : Dialog(context, R.style.Picture_Theme_AlertDialog) {

    init {
        setCancelable(true)
        setCanceledOnTouchOutside(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ps_alert_dialog)
        setDialogSize()
    }

    private fun setDialogSize() {
        val params = window!!.attributes
        params.width = ViewGroup.LayoutParams.WRAP_CONTENT
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.CENTER
        window!!.setWindowAnimations(R.style.PictureThemeDialogWindowStyle)
        window!!.attributes = params
    }
}
