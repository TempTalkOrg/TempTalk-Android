package com.luck.picture.lib.interfaces;

/**
 * @author：luck
 * @date：2022/7/1 23:25 下午
 * @describe：OnPlayerListener
 */
public interface OnPlayerListener {
    /**
     * player error
     */
    void onPlayerError();

    /**
     * playing
     */
    void onPlayerReady();

    /**
     * preparing to play
     */
    void onPlayerLoading();

    /**
     * end of playback
     */
    void onPlayerEnd();

    /**
     * Single tap on player view (for toggling UI visibility)
     * Default implementation does nothing for backward compatibility
     */
    default void onPlayerTap() {}

    /**
     * Long press on player view (for save/download actions)
     * Default implementation does nothing for backward compatibility
     */
    default void onPlayerLongPress() {}
}
