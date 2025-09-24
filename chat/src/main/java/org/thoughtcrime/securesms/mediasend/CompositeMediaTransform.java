package org.thoughtcrime.securesms.mediasend;

import android.content.Context;

import androidx.annotation.NonNull;

import com.luck.picture.lib.entity.LocalMedia;

/**
 * Allow multiple transforms to operate on {@link LocalMedia}. Care should
 * be taken on the order and implementation of combined transformers to prevent
 * one undoing the work of the other.
 */
public final class CompositeMediaTransform implements MediaTransform {

    private final MediaTransform[] transforms;

    public CompositeMediaTransform(MediaTransform... transforms) {
        this.transforms = transforms;
    }

    @Override
    public @NonNull LocalMedia transform(@NonNull Context context, @NonNull LocalMedia media) {
        LocalMedia updatedMedia = media;
        for (MediaTransform transform : transforms) {
            updatedMedia = transform.transform(context, updatedMedia);
        }
        return updatedMedia;
    }
}
