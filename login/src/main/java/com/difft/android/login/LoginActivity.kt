package com.difft.android.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupWindow
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ChativePopupView
import com.difft.android.base.widget.ChativePopupWindow
import com.difft.android.base.widget.floatAsTopBarButton
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

        mBinding.ibLoginMenu.visibility = View.VISIBLE
        mBinding.ibLoginMenu.floatAsTopBarButton(leading = false)
        mBinding.ibLoginMenu.setOnClickListener { showProxyMenu(it) }
        mBinding.root.liftLoginFormAboveKeyboard(mBinding.fragmentLogIn)
    }

    
    private fun showProxyMenu(anchor: View) {
        var popup: PopupWindow? = null
        val item = ChativePopupView.Item(label = getString(R.string.login_use_proxy)) {
            popup?.dismiss()
            openProxySettings()
        }
        popup = ChativePopupWindow.showAsDropDown2(anchor, listOf(item))
    }

    



    private fun openProxySettings() {
        runCatching {
            startActivity(
                Intent().setClassName(this, "com.difft.android.setting.ProxySettingsActivity")
            )
        }.onFailure { L.w(it) { "[Login] open proxy settings failed" } }
    }
}
