package com.difft.android.selector.dialog

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.difft.android.selector.R
import com.difft.android.selector.interfaces.OnItemClickListener
import com.difft.android.selector.utils.DensityUtil

class PhotoItemSelectedDialog : DialogFragment(), View.OnClickListener {
    private var isCancel = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val dialog = dialog
        if (dialog != null) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        return inflater.inflate(R.layout.ps_dialog_camera_selected, container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tvPicturePhoto = view.findViewById<TextView>(R.id.ps_tv_photo)
        val tvPictureVideo = view.findViewById<TextView>(R.id.ps_tv_video)
        val tvPictureCancel = view.findViewById<TextView>(R.id.ps_tv_cancel)
        tvPictureVideo.setOnClickListener(this)
        tvPicturePhoto.setOnClickListener(this)
        tvPictureCancel.setOnClickListener(this)
    }

    override fun onStart() {
        super.onStart()
        initDialogStyle()
    }

    private fun initDialogStyle() {
        val dialog = dialog
        if (dialog != null) {
            val window = dialog.window
            if (window != null) {
                window.setLayout(
                    DensityUtil.getRealScreenWidth(requireContext()),
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                )
                window.setGravity(Gravity.BOTTOM)
                window.setWindowAnimations(R.style.PictureThemeDialogFragmentAnim)
            }
        }
    }

    private var onItemClickListener: OnItemClickListener? = null

    fun setOnItemClickListener(onItemClickListener: OnItemClickListener?) {
        this.onItemClickListener = onItemClickListener
    }

    private var onDismissListener: OnDismissListener? = null

    fun setOnDismissListener(listener: OnDismissListener?) {
        this.onDismissListener = listener
    }

    interface OnDismissListener {
        fun onDismiss(isCancel: Boolean, dialog: DialogInterface)
    }

    override fun onClick(v: View) {
        val id = v.id
        val listener = onItemClickListener
        if (listener != null) {
            if (id == R.id.ps_tv_photo) {
                listener.onItemClick(v, IMAGE_CAMERA)
                isCancel = false
            } else if (id == R.id.ps_tv_video) {
                listener.onItemClick(v, VIDEO_CAMERA)
                isCancel = false
            }
        }
        dismissAllowingStateLoss()
    }

    override fun show(manager: FragmentManager, tag: String?) {
        val ft = manager.beginTransaction()
        ft.add(this, tag)
        ft.commitAllowingStateLoss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(isCancel, dialog)
    }

    companion object {
        const val IMAGE_CAMERA = 0
        const val VIDEO_CAMERA = 1

        @JvmStatic
        fun newInstance(): PhotoItemSelectedDialog = PhotoItemSelectedDialog()
    }
}
