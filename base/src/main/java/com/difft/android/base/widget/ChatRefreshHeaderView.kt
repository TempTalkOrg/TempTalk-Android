package com.difft.android.base.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import com.difft.android.base.R
import com.scwang.smart.refresh.layout.api.RefreshHeader
import com.scwang.smart.refresh.layout.api.RefreshLayout
import com.scwang.smart.refresh.layout.constant.RefreshState
import com.scwang.smart.refresh.layout.simple.SimpleComponent

class ChatRefreshHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SimpleComponent(context, attrs, defStyleAttr), RefreshHeader {

    init {
        val view: View = LayoutInflater.from(context).inflate(R.layout.chat_loading_header, this)
    }

    override fun onFinish(refreshLayout: RefreshLayout, success: Boolean): Int {
        super.onFinish(refreshLayout, success)
        return 0  //延迟50毫秒之后再弹回
    }

    override fun onStateChanged(refreshLayout: RefreshLayout, oldState: RefreshState, newState: RefreshState) {
        super.onStateChanged(refreshLayout, oldState, newState)

        when (newState) {
            RefreshState.PullDownToRefresh -> {}
            RefreshState.PullDownCanceled -> {}
            RefreshState.ReleaseToRefresh -> {}
            RefreshState.Refreshing -> {

            }
            else -> {

            }
        }
    }
}