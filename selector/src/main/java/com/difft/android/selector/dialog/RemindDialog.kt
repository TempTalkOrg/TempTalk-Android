package com.difft.android.selector.dialog

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.difft.android.selector.R

class RemindDialog(context: Context, tips: String) :
    Dialog(context, R.style.Picture_Theme_Dialog), View.OnClickListener {
    private val btnOk: TextView
    private val tvContent: TextView

    init {
        setContentView(R.layout.ps_remind_dialog)
        btnOk = findViewById(R.id.btnOk)
        tvContent = findViewById(R.id.tv_content)
        tvContent.text = tips
        btnOk.setOnClickListener(this)
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

    override fun onClick(view: View) {
        if (view.id == R.id.btnOk) {
            dismiss()
        }
    }

    companion object {
        @JvmStatic
        fun buildDialog(context: Context, tips: String): RemindDialog = RemindDialog(context, tips)
    }
}
