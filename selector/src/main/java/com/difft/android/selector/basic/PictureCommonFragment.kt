package com.difft.android.selector.basic

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.R
import com.difft.android.selector.app.PictureAppMaster
import com.difft.android.selector.config.Crop
import com.difft.android.selector.config.InjectResourceSource
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.dialog.PictureLoadingDialog
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.language.PictureLanguageUtils
import com.difft.android.selector.loader.IBridgeMediaLoader
import com.difft.android.selector.permissions.PermissionConfig
import com.difft.android.selector.permissions.PermissionResultCallback
import com.difft.android.selector.utils.ActivityCompatHelper
import com.difft.android.selector.utils.FileDirMap

/**
 * Base fragment of the selector/preview fragments. Owns the fragment lifecycle,
 * shared state, and the [IPictureSelectorCommonEvent] dispatch contract; the
 * heavy logic lives in 5 collaborators reached via this host reference.
 *
 * 1:1 Java→Kotlin port (issue #1077); the base + collaborator split is the only
 * structural change (500-line rule), all behavior preserved.
 */
abstract class PictureCommonFragment : Fragment(), IPictureSelectorCommonEvent {

    /** IBridgePictureBehavior */
    internal var iBridgePictureBehavior: IBridgePictureBehavior? = null

    /** page */
    internal var mPage = 1

    /** Media Loader engine */
    internal lateinit var mLoader: IBridgeMediaLoader

    /** PictureSelector Config */
    internal lateinit var selectorConfig: SelectorConfig

    /** Loading Dialog */
    private var mLoadingDialog: Dialog? = null

    /** click sound */
    private var soundPool: SoundPool? = null

    /** click sound effect id */
    private var soundID = 0

    /** fragment enter anim duration */
    private var enterAnimDuration: Long = 0

    /** tipsDialog */
    internal var tipsDialog: Dialog? = null

    /** attached context (last-resort fallback for getAppContext) */
    private var attachedContext: Context? = null

    internal val permissions = FragmentPermissionDispatcher(this)
    internal val camera = CameraCaptureController(this)
    internal val transform = MediaTransformPipeline(this)
    internal val validator = SelectionValidator(this)
    internal val results = FragmentResultDispatcher(this)

    open fun getFragmentTag(): String = TAG

    override fun onCreateLoader() {}

    override fun getResourceId(): Int = 0

    override fun onFragmentResume() {}

    override fun reStartSavedInstance(savedInstanceState: Bundle?) {}

    override fun onCheckOriginalChange() {}

    override fun dispatchCameraMediaResult(media: LocalMedia) {}

    override fun onSelectedChange(isAddRemove: Boolean, currentMedia: LocalMedia) {}

    override fun onFixedSelectedChange(oldLocalMedia: LocalMedia) {}

    override fun sendChangeSubSelectPositionEvent(adapterChange: Boolean) {}

    override fun onEditMedia(intent: Intent) {}

    override fun onEnterFragment() {}

    override fun onExitFragment() {}

    override fun handlePermissionSettingResult(permissions: Array<String>) {}

    override fun openSoundRecording() {
        // Audio-record path is dead (ofAudio is never launched); no-op retained for interface contract.
    }

    internal fun getAppContext(): Context {
        val ctx = context
        if (ctx != null) {
            return ctx
        }
        val appContext = PictureAppMaster.getInstance().getAppContext()
        if (appContext != null) {
            return appContext
        }
        return attachedContext!!
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        this.permissions.onRequestPermissionsResult(permissions, grantResults)
    }

    fun setPermissionsResultAction(callback: PermissionResultCallback?) {
        permissions.setPermissionsResultAction(callback)
    }

    override fun handlePermissionDenied(permissionArray: Array<String>) {
        permissions.handlePermissionDenied(permissionArray)
    }

    /** Whether entered via PictureSelector default flow. */
    internal fun isNormalDefaultEnter(): Boolean {
        return activity is PictureSelectorSupporterActivity || activity is PictureSelectorTransparentActivity
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        if (getResourceId() != InjectResourceSource.DEFAULT_LAYOUT_RESOURCE) {
            return inflater.inflate(getResourceId(), container, false)
        }
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectorConfig = SelectorProviders.getInstance().selectorConfig
        FileDirMap.init(view.context)
        selectorConfig.viewLifecycle?.onViewCreated(this, view, savedInstanceState)
        val customLoading = selectorConfig.onCustomLoadingListener
        mLoadingDialog = if (customLoading != null) {
            customLoading.create(getAppContext())
        } else {
            PictureLoadingDialog(getAppContext())
        }
        results.setRequestedOrientation()
        setRootViewKeyListener(requireView())
        if (selectorConfig.isOpenClickSound && !selectorConfig.isOnlyCamera) {
            val pool = SoundPool(1, AudioManager.STREAM_MUSIC, 0)
            soundPool = pool
            soundID = pool.load(getAppContext(), R.raw.ps_click_music, 1)
        }
    }

    /** Set the back-key listener. */
    fun setRootViewKeyListener(view: View) {
        if (selectorConfig.isNewKeyBackMode) {
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onKeyBackFragmentFinish()
                }
            })
        } else {
            view.isFocusableInTouchMode = true
            view.requestFocus()
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    onKeyBackFragmentFinish()
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        initAppLanguage()
    }

    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        val windowAnimationStyle = selectorConfig.selectorStyle.windowAnimationStyle!!
        val loadAnimation: Animation
        if (enter) {
            loadAnimation = if (windowAnimationStyle.activityEnterAnimation != 0) {
                AnimationUtils.loadAnimation(getAppContext(), windowAnimationStyle.activityEnterAnimation)
            } else {
                AnimationUtils.loadAnimation(getAppContext(), R.anim.ps_anim_alpha_enter)
            }
            setEnterAnimationDuration(loadAnimation.duration)
            onEnterFragment()
        } else {
            loadAnimation = if (windowAnimationStyle.activityExitAnimation != 0) {
                AnimationUtils.loadAnimation(getAppContext(), windowAnimationStyle.activityExitAnimation)
            } else {
                AnimationUtils.loadAnimation(getAppContext(), R.anim.ps_anim_alpha_exit)
            }
            onExitFragment()
        }
        return loadAnimation
    }

    fun setEnterAnimationDuration(duration: Long) {
        this.enterAnimDuration = duration
    }

    fun getEnterAnimationDuration(): Long {
        val duration = if (enterAnimDuration > 50) enterAnimDuration - 50 else enterAnimDuration
        return if (duration >= 0) duration else 0
    }

    override fun confirmSelect(currentMedia: LocalMedia, isSelected: Boolean): Int =
        validator.confirmSelect(currentMedia, isSelected)

    override fun checkWithMimeTypeValidity(
        media: LocalMedia, isSelected: Boolean, curMimeType: String,
        selectVideoSize: Int, fileSize: Long, duration: Long
    ): Boolean = validator.checkWithMimeTypeValidity(media, isSelected, curMimeType, selectVideoSize, fileSize, duration)

    override fun checkOnlyMimeTypeValidity(
        media: LocalMedia, isSelected: Boolean, curMimeType: String,
        existMimeType: String, fileSize: Long, duration: Long
    ): Boolean = validator.checkOnlyMimeTypeValidity(media, isSelected, curMimeType, existMimeType, fileSize, duration)

    override fun sendSelectedChangeEvent(isAddRemove: Boolean, currentMedia: LocalMedia) {
        if (!ActivityCompatHelper.isDestroy(activity)) {
            val fragments = requireActivity().supportFragmentManager.fragments
            for (i in fragments.indices) {
                val fragment = fragments[i]
                if (fragment is PictureCommonFragment) {
                    fragment.onSelectedChange(isAddRemove, currentMedia)
                }
            }
        }
    }

    override fun sendFixedSelectedChangeEvent(currentMedia: LocalMedia) {
        if (!ActivityCompatHelper.isDestroy(activity)) {
            val fragments = requireActivity().supportFragmentManager.fragments
            for (i in fragments.indices) {
                val fragment = fragments[i]
                if (fragment is PictureCommonFragment) {
                    fragment.onFixedSelectedChange(currentMedia)
                }
            }
        }
    }

    override fun sendSelectedOriginalChangeEvent() {
        if (!ActivityCompatHelper.isDestroy(activity)) {
            val fragments = requireActivity().supportFragmentManager.fragments
            for (i in fragments.indices) {
                val fragment = fragments[i]
                if (fragment is PictureCommonFragment) {
                    fragment.onCheckOriginalChange()
                }
            }
        }
    }

    override fun openSelectedCamera() = camera.openSelectedCamera()

    override fun onSelectedOnlyCamera() = camera.onSelectedOnlyCamera()

    override fun openImageCamera() = camera.openImageCamera()

    override fun openVideoCamera() = camera.openVideoCamera()

    override fun onApplyPermissionsEvent(event: Int, permissionArray: Array<String>) {
        permissions.onApplyPermissionsEvent(event, permissionArray)
    }

    override fun onPermissionExplainEvent(isDisplayExplain: Boolean, permissionArray: Array<String>) {
        permissions.onPermissionExplainEvent(isDisplayExplain, permissionArray)
    }

    /** Play the click sound effect. */
    internal fun playClickEffect() {
        val pool = soundPool
        if (pool != null && selectorConfig.isOpenClickSound) {
            pool.play(soundID, 0.1f, 0.5f, 0, 1, 1f)
        }
    }

    /** Release the sound-effect resources. */
    private fun releaseSoundPool() {
        try {
            val pool = soundPool
            if (pool != null) {
                pool.release()
                soundPool = null
            }
        } catch (e: Exception) {
            L.w(e) { "[PictureCommonFragment] releaseSoundPool error:" }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                PictureConfig.REQUEST_CAMERA -> camera.dispatchHandleCamera(data)
                Crop.REQUEST_EDIT_CROP -> onEditMedia(data!!)
                Crop.REQUEST_CROP -> transform.handleCropResult(data)
            }
        } else if (resultCode == Crop.RESULT_CROP_ERROR) {
            transform.handleCropError(data)
        } else if (resultCode == Activity.RESULT_CANCELED) {
            if (requestCode == PictureConfig.REQUEST_CAMERA) {
                camera.handleCameraCancel()
            } else if (requestCode == PictureConfig.REQUEST_GO_SETTING) {
                handlePermissionSettingResult(PermissionConfig.CURRENT_REQUEST_PERMISSION)
            }
        }
    }

    protected open fun dispatchTransformResult() = transform.dispatchTransformResult()

    override fun onCrop(result: ArrayList<LocalMedia>) = transform.onCrop(result)

    @Deprecated("")
    override fun onOldCrop(result: ArrayList<LocalMedia>) = transform.onOldCrop(result)

    override fun onCompress(result: ArrayList<LocalMedia>) = transform.onCompress(result)

    @Deprecated("")
    override fun onOldCompress(result: ArrayList<LocalMedia>) = transform.onOldCompress(result)

    override fun checkCropValidity(): Boolean = transform.checkCropValidity()

    @Deprecated("")
    override fun checkOldCropValidity(): Boolean = transform.checkOldCropValidity()

    override fun checkCompressValidity(): Boolean = transform.checkCompressValidity()

    @Deprecated("")
    override fun checkOldCompressValidity(): Boolean = transform.checkOldCompressValidity()

    override fun checkTransformSandboxFile(): Boolean = transform.checkTransformSandboxFile()

    @Deprecated("")
    override fun checkOldTransformSandboxFile(): Boolean = transform.checkOldTransformSandboxFile()

    override fun onResultEvent(result: ArrayList<LocalMedia>) = transform.onResultEvent(result)

    /** Set the app language. */
    override fun initAppLanguage() {
        if (!::selectorConfig.isInitialized) {
            selectorConfig = SelectorProviders.getInstance().selectorConfig
        }
        if (selectorConfig.language != LanguageConfig.UNKNOWN_LANGUAGE) {
            activity?.let {
                PictureLanguageUtils.setAppLanguage(it, selectorConfig.language, selectorConfig.defaultLanguage)
            }
        }
    }

    override fun onRecreateEngine() = results.onRecreateEngine()

    override fun onKeyBackFragmentFinish() = results.onKeyBackFragmentFinish()

    override fun onDestroy() {
        releaseSoundPool()
        super.onDestroy()
    }

    override fun showLoading() {
        try {
            if (ActivityCompatHelper.isDestroy(activity)) {
                return
            }
            mLoadingDialog?.let { if (!it.isShowing) it.show() }
        } catch (e: Exception) {
            L.w(e) { "[PictureCommonFragment] showLoading error:" }
        }
    }

    override fun dismissLoading() {
        try {
            if (ActivityCompatHelper.isDestroy(activity)) {
                return
            }
            mLoadingDialog?.let { if (it.isShowing) it.dismiss() }
        } catch (e: Exception) {
            L.w(e) { "[PictureCommonFragment] dismissLoading error:" }
        }
    }

    override fun onAttach(context: Context) {
        initAppLanguage()
        onRecreateEngine()
        super.onAttach(context)
        this.attachedContext = context
        val parent = parentFragment
        if (parent is IBridgePictureBehavior) {
            iBridgePictureBehavior = parent
        } else if (context is IBridgePictureBehavior) {
            iBridgePictureBehavior = context
        }
    }

    /** Back to the current fragment. */
    protected open fun onBackCurrentFragment() = results.onBackCurrentFragment()

    /** Exit PictureSelector. */
    open fun onExitPictureSelector() = results.onExitPictureSelector()

    companion object {
        @JvmField
        val TAG: String = PictureCommonFragment::class.java.simpleName
    }

    /** SelectorResult */
    class SelectorResult(
        @JvmField var mResultCode: Int,
        @JvmField var mResultData: Intent?
    )
}
