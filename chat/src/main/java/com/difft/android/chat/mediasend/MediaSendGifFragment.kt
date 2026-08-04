package com.difft.android.chat.mediasend

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.difft.android.chat.R

class MediaSendGifFragment : Fragment(), MediaSendPageFragment {

    private var uri: Uri? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.mediasend_image_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let { args ->
            uri = args.getParcelable(KEY_URI)
            uri?.let {
                // Load the URI itself: File(uri.path) threw NPE for a path-less URI and resolved to
                // a non-existent path for a content one. Glide's Uri loader handles every scheme.
                Glide.with(this).load(it).fitCenter().into(view as ImageView)
            }
        }
    }

    override fun setUri(uri: Uri) {
        this.uri = uri
    }

    override fun getUri(): Uri = uri!!

    override fun saveState(): Any? = null

    override fun restoreState(state: Any) {}

    override fun notifyHidden() {}

    companion object {
        private const val KEY_URI = "uri"

        @JvmStatic
        fun newInstance(uri: Uri): MediaSendGifFragment {
            val args = Bundle()
            args.putParcelable(KEY_URI, uri)

            val fragment = MediaSendGifFragment()
            fragment.arguments = args
            fragment.setUri(uri)
            return fragment
        }
    }
}
