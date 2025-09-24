package com.difft.android.login.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.utils.dp
import com.difft.android.login.BindAccountActivity
import com.difft.android.login.R
import com.difft.android.login.VerifyCodeActivity
import com.difft.android.login.databinding.FragmentLogInBinding
import com.difft.android.login.viewmodel.LoginViewModel
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.viewmodel.Status
import com.difft.android.base.widget.setTopMargin
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.util.ViewUtil
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class LoginFragment : Fragment() {

    private val viewModel by lazy {
        ViewModelProvider(this)[LoginViewModel::class.java]
    }

    private val binding: FragmentLogInBinding by viewbind()

    @Inject
    lateinit var userManager: UserManager

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initView()
        startObserve()
    }

    private fun initView() {
        binding.account.visibility = View.VISIBLE
        binding.errorHint.visibility = View.GONE
        disableHandleZone()
        binding.handleZone.setOnClickListener {
            checkInputAccount()
        }

        binding.account.doOnTextChanged { text, _, _, _ ->
            val content = text.toString().trim()
            if (content.isEmpty()) {
                disableHandleZone()
            } else {
                enableHandleZone()
                if (ValidatorUtil.isPhone(content)) {
                    binding.clPhone.visibility = View.VISIBLE
                } else {
                    binding.clPhone.visibility = View.GONE
                }
            }
        }

        binding.clPhone.setOnClickListener {
            val intent = Intent(requireActivity(), CountryPickerActivity::class.java)
            countryPickerActivityLauncher.launch(intent)
        }
        binding.tvPhoneCode.text = getDefaultCountryCode()

        binding.account.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                checkInputAccount()
            }
            false
        }

        binding.tvSignUp.setOnClickListener {
            val fragmentLogIn = requireActivity().findViewById<View>(R.id.fragment_log_in)
            val fragmentSignUp = requireActivity().findViewById<View>(R.id.fragment_sign_up)

            fragmentLogIn.visibility = View.GONE
            fragmentSignUp.visibility = View.VISIBLE

            ViewUtil.hideKeyboard(requireContext(), binding.root)
        }
    }

    private fun getDefaultCountryCode(): String {
        val defaultRegion = Locale.getDefault().country
        val phoneNumberUtil = PhoneNumberUtil.getInstance()
        val countryCode = phoneNumberUtil.getCountryCodeForRegion(defaultRegion)
        return "+$countryCode"
    }

    private val countryPickerActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            val code = it.data?.getStringExtra("code")
            binding.tvPhoneCode.text = code
        }
    }

    private fun checkInputAccount() {
        showSignInErrorView(null)
        val account = binding.account.text.toString().trim()
        if (account.isEmpty()) return
        val phoneCode = binding.tvPhoneCode.text.toString().trim()
        viewModel.verifyAccount(account, phoneCode)
    }

    private fun disableHandleZone() {
        binding.handleZone.isEnabled = false
        binding.animationView.visibility = View.GONE
        binding.nextText.visibility = View.VISIBLE
        binding.nextText.setTextColor(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_disable))
    }

    private fun enableHandleZone() {
        binding.handleZone.isEnabled = true
        binding.animationView.visibility = View.GONE
        binding.nextText.visibility = View.VISIBLE
        binding.nextText.setTextColor(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_white))
    }

    private fun loadingHandleZone() {
        binding.handleZone.isEnabled = true
        binding.animationView.visibility = View.VISIBLE
        binding.nextText.visibility = View.GONE
    }

    private fun startObserve() {
        observeVerifyInviteCode()
        observeSignIn()
        observeVerifyPhone()
        observeVerifyEmail()
    }

    private fun observeVerifyInviteCode() =
        viewModel.inviteCodeLiveData.observe(
            viewLifecycleOwner
        ) {
            it ?: return@observe
            when (it.status) {
                Status.LOADING -> loadingHandleZone()
                Status.SUCCESS -> enableHandleZone()
                Status.ERROR -> {
                    enableHandleZone()
                    showSignInErrorView(it.exception?.errorMsg)
                }
            }
        }

    //邀请码登录成功，会进入绑定邮箱流程，新版本去掉了邀请码登录的功能，所以此逻辑不会触发
    private fun observeSignIn() =
        viewModel.signInLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.LOADING -> loadingHandleZone()
                Status.SUCCESS -> {
                    enableHandleZone()
                    startActivity(Intent(context, BindAccountActivity::class.java))
                    activity?.finish()
                }

                Status.ERROR -> {
                    enableHandleZone()
                    showSignInErrorView(it.exception?.errorMsg)
                }
            }
        }

    private fun observeVerifyPhone() =
        viewModel.verifyPhoneLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.LOADING -> loadingHandleZone()
                Status.SUCCESS -> {
                    enableHandleZone()
                    val bundle = VerifyCodeActivity.createBundle(true, VerifyCodeActivity.TYPE_BIND_PHONE, it.data ?: "")
                    startActivity(Intent(context, VerifyCodeActivity::class.java).putExtras(bundle))
                }

                Status.ERROR -> {
                    enableHandleZone()
                    showSignInErrorView(it.exception?.errorMsg)
                }
            }
        }

    private fun observeVerifyEmail() =
        viewModel.verifyEmailLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.LOADING -> loadingHandleZone()
                Status.SUCCESS -> {
                    enableHandleZone()
                    val bundle = VerifyCodeActivity.createBundle(true, VerifyCodeActivity.TYPE_BIND_EMAIL, it.data ?: "")
                    startActivity(Intent(context, VerifyCodeActivity::class.java).putExtras(bundle))
                }

                Status.ERROR -> {
                    enableHandleZone()
                    showSignInErrorView(it.exception?.errorMsg)
                }
            }
        }

    private fun showSignInErrorView(errorMessage: String?) {
        if (!errorMessage.isNullOrEmpty()) {
            binding.clAccount.background = ResUtils.getDrawable(R.drawable.login_account_error_border)
            binding.errorHint.text = errorMessage
            binding.errorHint.visibility = View.VISIBLE
            binding.handleZone.setTopMargin(20.dp)
        } else {
            binding.clAccount.background = ResUtils.getDrawable(R.drawable.login_account_border)
            binding.errorHint.visibility = View.GONE
            binding.handleZone.setTopMargin(16.dp)
        }
    }
}