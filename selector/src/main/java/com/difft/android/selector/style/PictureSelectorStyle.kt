package com.difft.android.selector.style

class PictureSelectorStyle {

    private var titleBarStyleField: TitleBarStyle? = null

    /** Getter never returns null (1:1 with the Java getter). */
    var titleBarStyle: TitleBarStyle?
        get() = titleBarStyleField ?: TitleBarStyle()
        set(value) {
            titleBarStyleField = value
        }

    private var selectMainStyleField: SelectMainStyle? = null

    var selectMainStyle: SelectMainStyle?
        get() = selectMainStyleField ?: SelectMainStyle()
        set(value) {
            selectMainStyleField = value
        }

    private var bottomBarStyleField: BottomNavBarStyle? = null

    var bottomBarStyle: BottomNavBarStyle?
        get() = bottomBarStyleField ?: BottomNavBarStyle()
        set(value) {
            bottomBarStyleField = value
        }

    private var windowAnimationStyleField: PictureWindowAnimationStyle? = null

    /** Lazily initializes the default window-animation style (1:1 with the Java getter). */
    var windowAnimationStyle: PictureWindowAnimationStyle?
        get() {
            if (windowAnimationStyleField == null) {
                windowAnimationStyleField =
                    PictureWindowAnimationStyle.ofDefaultWindowAnimationStyle()
            }
            return windowAnimationStyleField
        }
        set(value) {
            windowAnimationStyleField = value
        }

    private var albumWindowStyleField: AlbumWindowStyle? = null

    var albumWindowStyle: AlbumWindowStyle?
        get() = albumWindowStyleField ?: AlbumWindowStyle()
        set(value) {
            albumWindowStyleField = value
        }
}
