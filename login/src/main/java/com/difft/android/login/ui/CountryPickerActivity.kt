package com.difft.android.login.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.BaseActivity
import com.difft.android.login.databinding.ActivityCountryPickerBinding
import com.difft.android.base.widget.sideBar.CharacterParser
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class CountryPickerActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, CountryPickerActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityCountryPickerBinding by viewbind()

    private val countryPickerAdapter: CountryPickerAdapter by lazy {
        object : CountryPickerAdapter() {
            override fun onItemClick(data: CountryItem) {
                setResult(RESULT_OK, Intent().apply { putExtra("code", data.code) })
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.edittextSearchInput.addTextChangedListener {
            resetButtonClear()
            updateResult()
        }

        mBinding.buttonClear.setOnClickListener {
            mBinding.edittextSearchInput.text = null
        }

        resetButtonClear()

        mBinding.rvCountry.apply {
            layoutManager = LinearLayoutManager(this@CountryPickerActivity)
            adapter = countryPickerAdapter
            itemAnimator = null
        }

        updateResult()
    }

    private val countryList: List<CountryItem> by lazy {
        val supportedRegions = PhoneNumberUtil.getInstance().supportedRegions

        supportedRegions.map { region ->
            val countryName = Locale("", region).getDisplayCountry(Locale.getDefault())
            val countryNamePinyin = CharacterParser.getSelling(countryName)
            val countryCode = PhoneNumberUtil.getInstance().getCountryCodeForRegion(region)
            CountryItem(countryName, "+$countryCode", countryNamePinyin)
        }.sortedBy { it.countryNamePinyin }
    }

    private fun updateResult() {
        val key = mBinding.edittextSearchInput.text.toString().trim()

        val searchList = if (key.isEmpty()) {
            countryList
        } else {
            countryList.filter { it.name.contains(key) || it.code.contains(key) }
        }
        countryPickerAdapter.submitList(searchList) {
            mBinding.rvCountry.scrollToPosition(0)
        }
    }

    private fun resetButtonClear() {
        val content = mBinding.edittextSearchInput.text.toString().trim()
        mBinding.buttonClear.animate().apply {
            cancel()
            val toAlpha = if (content.isNotEmpty()) 1.0f else 0f
            alpha(toAlpha)
        }
    }
}
