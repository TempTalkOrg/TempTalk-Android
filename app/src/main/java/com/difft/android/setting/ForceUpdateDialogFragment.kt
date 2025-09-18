package com.difft.android.setting

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import com.difft.android.R

class ForceUpdateDialogFragment(val message: String? = "", private val onClickListener: View.OnClickListener? = null) : DialogFragment() {

    init {
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = AlertDialog.Builder(requireActivity())
            .setCancelable(false)
            .setTitle(requireActivity().getString(R.string.settings_check_new_version))
            .setMessage(message)
            .setPositiveButton(requireActivity().getString(R.string.settings_dialog_update), null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(onClickListener)

        return dialog
    }
}