package org.thoughtcrime.securesms.components;

import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.TranslateAnimation;

import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture;
import org.thoughtcrime.securesms.util.concurrent.SettableFuture;

public class SlideToCancel {
    private static final int FADE_TIME = 150;

    private final View slideToCancelView;

    public SlideToCancel(View slideToCancelView) {
        this.slideToCancelView = slideToCancelView;
    }

    public void display() {
        ViewUtil.fadeIn(this.slideToCancelView, FADE_TIME);
    }

    public ListenableFuture<Void> hide() {
        final SettableFuture<Void> future = new SettableFuture<>();

        AnimationSet animation = new AnimationSet(true);
        animation.addAnimation(new TranslateAnimation(Animation.ABSOLUTE, slideToCancelView.getTranslationX(),
                Animation.ABSOLUTE, 0,
                Animation.RELATIVE_TO_SELF, 0,
                Animation.RELATIVE_TO_SELF, 0));
        animation.addAnimation(new AlphaAnimation(1, 0));

        animation.setDuration(MicrophoneRecorderView.ANIMATION_DURATION);
        animation.setFillBefore(true);
        animation.setFillAfter(false);

        slideToCancelView.postDelayed(() -> future.set(null), MicrophoneRecorderView.ANIMATION_DURATION);
        slideToCancelView.setVisibility(View.GONE);
        slideToCancelView.startAnimation(animation);

        return future;
    }

    public void moveTo(float offset) {
        Animation animation = new TranslateAnimation(Animation.ABSOLUTE, offset,
                Animation.ABSOLUTE, offset,
                Animation.RELATIVE_TO_SELF, 0,
                Animation.RELATIVE_TO_SELF, 0);

        animation.setDuration(0);
        animation.setFillAfter(true);
        animation.setFillBefore(true);

        slideToCancelView.startAnimation(animation);
    }
}
