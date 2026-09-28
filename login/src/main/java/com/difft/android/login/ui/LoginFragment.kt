package com.difft.android.login.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.utils.dp
import com.difft.android.login.R
import com.difft.android.login.VerifyCodeActivity
import com.difft.android.login.databinding.FragmentLogInBinding
import com.difft.android.login.viewmodel.LoginViewModel
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.viewmodel.Status
import com.difft.android.base.widget.WaitDialogHandle
import com.difft.android.base.widget.setTopMargin
import com.difft.android.base.widget.waitDialogHandle
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import com.difft.android.chat.util.ViewUtil
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class LoginFragment : Fragment() {

    private val viewModel by lazy {
        ViewModelProvider(this)[LoginViewModel::class.java]
    }

    private val binding: FragmentLogInBinding by viewbind()

    /**
     * The next button's own wait dialog: only the account request may raise or release it, and the
     * view lifecycle releases it on destruction.
     */
    private lateinit var nextWait: WaitDialogHandle

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

        nextWait = waitDialogHandle()
        initView()
        startObserve()
    }

    private fun initView() {
        binding.account.visibility = View.VISIBLE
        binding.errorHint.visibility = View.GONE
        disableNext()
        binding.btnNext.setOnClickListener {
            checkInputAccount()
        }

        binding.account.onQueryChanged = { text ->
            val content = text.trim()
            if (content.isEmpty()) {
                disableNext()
            } else {
                enableNext()
                if (ValidatorUtil.isPhone(content)) {
                    binding.clPhone.visibility = View.VISIBLE
                } else {
                    binding.clPhone.visibility = View.GONE
                }
            }
        }
        // Equivalence note: today's empty branch only disables the button — clPhone stays as-is
        // after a clear (it only changes on the next non-empty input). Do NOT hide it here.
        binding.account.onClear = { disableNext() }

        binding.clPhone.setOnClickListener {
            val intent = Intent(requireActivity(), CountryPickerActivity::class.java)
            countryPickerActivityLauncher.launch(intent)
        }
        binding.tvPhoneCode.text = getDefaultCountryCode()

        binding.account.onImeAction = { checkInputAccount() }

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

    private val countryPickerActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Activity-result callbacks fire after view destruction; guard binding access.
        if (!isAdded || view == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val code = result.data?.getStringExtra("code") ?: return@registerForActivityResult
        binding.tvPhoneCode.text = code
    }

    private fun checkInputAccount() {
        showSignInErrorView(null)
        val account = binding.account.query.trim()
        if (account.isEmpty()) return
        val phoneCode = binding.tvPhoneCode.text.toString().trim()
        viewModel.verifyAccount(account, phoneCode)
    }

    private fun disableNext() {
        binding.btnNext.isEnabled = false
    }

    /** Input validity only: a keystroke must never release a dialog the request raised. */
    private fun enableNext() {
        binding.btnNext.isEnabled = true
    }

    private fun loadingNext() {
        nextWait.show()
    }

    private fun startObserve() {
        observeAccountInvalid()
        observeVerifyPhone()
        observeVerifyEmail()
    }

    // Local validation error: input is neither a valid email nor a phone number.
    private fun observeAccountInvalid() =
        viewModel.accountInvalidLiveData.observe(viewLifecycleOwner) {
            it ?: return@observe
            if (it.status == Status.ERROR) {
                enableNext()
                showSignInErrorView(it.exception?.errorMsg)
            }
        }

    private fun observeVerifyPhone() =
        viewModel.verifyPhoneLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.LOADING -> loadingNext()
                Status.SUCCESS -> {
                    // Request settled: release the non-cancelable modal before the code screen
                    // covers this one.
                    nextWait.dismiss()
                    enableNext()
                    val bundle = VerifyCodeActivity.createBundle(true, VerifyCodeActivity.TYPE_BIND_PHONE, it.data ?: "")
                    startActivity(Intent(context, VerifyCodeActivity::class.java).putExtras(bundle))
                }

                Status.ERROR -> {
                    nextWait.dismiss()
                    enableNext()
                    showSignInErrorView(it.exception?.errorMsg)
                }
            }
        }

    private fun observeVerifyEmail() =
        viewModel.verifyEmailLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.LOADING -> loadingNext()
                Status.SUCCESS -> {
                    // Request settled: release the non-cancelable modal before the code screen
                    // covers this one.
                    nextWait.dismiss()
                    enableNext()
                    val bundle = VerifyCodeActivity.createBundle(true, VerifyCodeActivity.TYPE_BIND_EMAIL, it.data ?: "")
                    startActivity(Intent(context, VerifyCodeActivity::class.java).putExtras(bundle))
                }

                Status.ERROR -> {
                    nextWait.dismiss()
                    enableNext()
                    showSignInErrorView(it.exception?.errorMsg)
                }
            }
        }

    private fun showSignInErrorView(errorMessage: String?) {
        if (!errorMessage.isNullOrEmpty()) {
            binding.clAccount.background = ResUtils.getDrawable(R.drawable.login_account_error_border)
            binding.errorHint.text = errorMessage
            binding.errorHint.visibility = View.VISIBLE
            binding.btnNext.setTopMargin(20.dp)
        } else {
            binding.clAccount.background = ResUtils.getDrawable(R.drawable.login_account_border)
            binding.errorHint.visibility = View.GONE
            binding.btnNext.setTopMargin(16.dp)
        }
    }
}