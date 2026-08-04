package com.difft.android.selector.style

class SelectMainStyle {

    var statusBarColor: Int = 0
    var navigationBarColor: Int = 0

    /** Status bar font color: either black or white. */
    var isDarkStatusBarBlack: Boolean = false

    var isCompleteSelectRelativeTop: Boolean = false
    var isPreviewSelectRelativeBottom: Boolean = false
    var isPreviewDisplaySelectGallery: Boolean = false

    /** unit dp */
    var previewSelectMarginRight: Int = 0

    var previewBackgroundColor: Int = 0

    var previewSelectText: String? = null
    var previewSelectTextResId: Int = 0
    var previewSelectTextSize: Int = 0
    var previewSelectTextColor: Int = 0

    var selectBackground: Int = 0
    var previewSelectBackground: Int = 0

    var isSelectNumberStyle: Boolean = false
    var isPreviewSelectNumberStyle: Boolean = false

    var mainListBackgroundColor: Int = 0

    var selectNormalText: String? = null
    var selectNormalTextResId: Int = 0
    var selectNormalTextSize: Int = 0
    var selectNormalTextColor: Int = 0
    var selectNormalBackgroundResources: Int = 0

    var selectText: String? = null
    var selectTextResId: Int = 0
    var selectTextSize: Int = 0
    var selectTextColor: Int = 0
    var selectBackgroundResources: Int = 0

    /** unit dp */
    var adapterItemSpacingSize: Int = 0

    var isAdapterItemIncludeEdge: Boolean = false
    var adapterSelectTextSize: Int = 0

    /** unit dp */
    var adapterSelectClickArea: Int = 0

    var adapterSelectTextColor: Int = 0

    /** position via RelativeLayout.addRule() */
    var adapterSelectStyleGravity: IntArray? = null

    var adapterDurationDrawableLeft: Int = 0
    var adapterDurationTextSize: Int = 0
    var adapterDurationTextColor: Int = 0

    /** position via RelativeLayout.addRule() */
    var adapterDurationGravity: IntArray? = null

    var adapterDurationBackgroundResources: Int = 0

    var adapterCameraBackgroundColor: Int = 0
    var adapterCameraDrawableTop: Int = 0
    var adapterCameraText: String? = null
    var adapterCameraTextResId: Int = 0
    var adapterCameraTextColor: Int = 0
    var adapterCameraTextSize: Int = 0

    var adapterTagBackgroundResources: Int = 0
    var adapterTagTextSize: Int = 0
    var adapterTagTextColor: Int = 0

    /** position via RelativeLayout.addRule() */
    var adapterTagGravity: IntArray? = null

    var adapterImageEditorResources: Int = 0

    /** position via RelativeLayout.addRule() */
    var adapterImageEditorGravity: IntArray? = null

    var adapterPreviewGalleryFrameResource: Int = 0
    var adapterPreviewGalleryBackgroundResource: Int = 0

    /** unit dp */
    var adapterPreviewGalleryItemSize: Int = 0
}
