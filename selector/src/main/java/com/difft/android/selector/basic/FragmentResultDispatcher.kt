package com.difft.android.selector.basic

import android.app.Activity
import com.difft.android.selector.app.PictureAppMaster
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.utils.ActivityCompatHelper

/**
 * Both result paths (activity-result + callback) plus lazy engine
 * instantiation. Extracted from PictureCommonFragment (issue #1077).
 */
internal class FragmentResultDispatcher(private val host: PictureCommonFragment) {

    private val config get() = host.selectorConfig

    /** Return the processed selection result. */
    fun onCallBackResult(result: ArrayList<LocalMedia>) {
        if (!ActivityCompatHelper.isDestroy(host.activity)) {
            host.dismissLoading()
            if (config.isActivityResultBack) {
                host.requireActivity().setResult(Activity.RESULT_OK, PictureSelector.putIntentResult(result))
                onSelectFinish(Activity.RESULT_OK, result)
            } else {
                config.onResultCallListener?.onResult(result)
            }
            onExitPictureSelector()
        }
    }

    fun onSelectFinish(resultCode: Int, result: ArrayList<LocalMedia>?) {
        val behavior = host.iBridgePictureBehavior
        if (behavior != null) {
            val selectorResult = getResult(resultCode, result)
            behavior.onSelectFinish(selectorResult)
        }
    }

    fun getResult(resultCode: Int, data: ArrayList<LocalMedia>?): PictureCommonFragment.SelectorResult {
        return PictureCommonFragment.SelectorResult(
            resultCode,
            if (data != null) PictureSelector.putIntentResult(data) else null
        )
    }

    fun onKeyBackFragmentFinish() {
        if (!ActivityCompatHelper.isDestroy(host.activity)) {
            if (config.isActivityResultBack) {
                host.requireActivity().setResult(Activity.RESULT_CANCELED)
                onSelectFinish(Activity.RESULT_CANCELED, null)
            } else {
                config.onResultCallListener?.onCancel()
            }
            onExitPictureSelector()
        }
    }

    fun setRequestedOrientation() {
        if (ActivityCompatHelper.isDestroy(host.activity)) {
            return
        }
        host.requireActivity().requestedOrientation = config.requestedOrientation
    }

    /** Back to the current fragment. */
    fun onBackCurrentFragment() {
        if (!ActivityCompatHelper.isDestroy(host.activity)) {
            if (!host.isStateSaved) {
                config.viewLifecycle?.onDestroy(host)
                host.requireActivity().supportFragmentManager.popBackStack()
            }
            val fragments = host.requireActivity().supportFragmentManager.fragments
            for (i in fragments.indices) {
                val fragment = fragments[i]
                if (fragment is PictureCommonFragment) {
                    fragment.onFragmentResume()
                }
            }
        }
    }

    /** Exit PictureSelector. */
    fun onExitPictureSelector() {
        if (!ActivityCompatHelper.isDestroy(host.activity)) {
            if (host.isNormalDefaultEnter()) {
                config.viewLifecycle?.onDestroy(host)
                host.requireActivity().finish()
            } else {
                val fragments = host.requireActivity().supportFragmentManager.fragments
                for (i in fragments.indices) {
                    val fragment = fragments[i]
                    if (fragment is PictureCommonFragment) {
                        onBackCurrentFragment()
                    }
                }
            }
        }
        SelectorProviders.getInstance().destroy()
    }

    fun onRecreateEngine() {
        createImageLoaderEngine()
        createVideoPlayerEngine()
        createCompressEngine()
        createSandboxFileEngine()
        createLoaderDataEngine()
        createResultCallbackListener()
        createLayoutResourceListener()
    }

    private fun createImageLoaderEngine() {
        if (config.imageEngine == null) {
            PictureAppMaster.getInstance().getPictureSelectorEngine()?.let {
                config.imageEngine = it.createImageLoaderEngine()
            }
        }
    }

    private fun createVideoPlayerEngine() {
        if (config.videoPlayerEngine == null) {
            PictureAppMaster.getInstance().getPictureSelectorEngine()?.let {
                config.videoPlayerEngine = it.createVideoPlayerEngine()
            }
        }
    }

    private fun createLoaderDataEngine() {
        if (config.isLoaderDataEngine) {
            if (config.loaderDataEngine == null) {
                PictureAppMaster.getInstance().getPictureSelectorEngine()?.let {
                    config.loaderDataEngine = it.createLoaderDataEngine()
                }
            }
        }
        if (config.isLoaderFactoryEngine) {
            if (config.loaderFactory == null) {
                PictureAppMaster.getInstance().getPictureSelectorEngine()?.let {
                    config.loaderFactory = it.onCreateLoader()
                }
            }
        }
    }

    private fun createCompressEngine() {
        if (config.isCompressEngine) {
            if (config.compressFileEngine == null) {
                PictureAppMaster.getInstance().getPictureSelectorEngine()?.let {
                    config.compressFileEngine = it.createCompressFileEngine()
                }
            }
            if (config.compressEngine == null) {
                PictureAppMaster.getInstance().getPictureSelectorEngine()?.let {
                    config.compressEngine = it.createCompressEngine()
                }
            }
        }
    }

    private fun createSandboxFileEngine() {
        if (config.isSandboxFileEngine) {
            if (config.uriToFileTransformEngine == null) {
                PictureAppMaster.getInstance().getPictureSelectorEngine()?.let {
                    config.uriToFileTransformEngine = it.createUriToFileTransformEngine()
                }
            }
            if (config.sandboxFileEngine == null) {
                PictureAppMaster.getInstance().getPictureSelectorEngine()?.let {
                    config.sandboxFileEngine = it.createSandboxFileEngine()
                }
            }
        }
    }

    private fun createResultCallbackListener() {
        if (config.isResultListenerBack) {
            if (config.onResultCallListener == null) {
                PictureAppMaster.getInstance().getPictureSelectorEngine()?.let {
                    config.onResultCallListener = it.getResultCallbackListener()
                }
            }
        }
    }

    private fun createLayoutResourceListener() {
        if (config.isInjectLayoutResource) {
            if (config.onLayoutResourceListener == null) {
                PictureAppMaster.getInstance().getPictureSelectorEngine()?.let {
                    config.onLayoutResourceListener = it.createLayoutResourceListener()
                }
            }
        }
    }
}
