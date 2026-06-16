package com.difft.android.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
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

        // Proxy entry is only available in insider builds
        mBinding.ibLoginMenu.visibility =
            if (globalServices.environmentHelper.isInsiderChannel()) View.VISIBLE else View.GONE
        mBinding.ibLoginMenu.setOnClickListener { showProxyMenu(it) }
    }

    private fun showProxyMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_USE_PROXY, 0, getString(R.string.login_use_proxy))
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_USE_PROXY) {
                    openProxySettings()
                    true
                } else {
                    false
                }
            }
            show()
        }
    }

    /**
     * Starts the proxy settings screen, which lives in the :app module. :login
     * cannot depend on :app, so launch it by explicit component class name.
     */
    private fun openProxySettings() {
        runCatching {
            startActivity(
                Intent().setClassName(this, "com.difft.android.setting.ProxySettingsActivity")
            )
        }.onFailure { L.w(it) { "[Login] open proxy settings failed" } }
    }

    companion object {
        private const val MENU_USE_PROXY = 1
    }
}
