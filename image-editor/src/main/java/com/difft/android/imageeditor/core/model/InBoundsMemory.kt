package com.difft.android.imageeditor.core.model

import android.graphics.Matrix

class InBoundsMemory {

    private val lastGoodUserCrop = Matrix()
    private val lastGoodMainImage = Matrix()

    fun push(mainImage: EditorElement?, userCrop: EditorElement) {
        if (mainImage == null) {
            lastGoodMainImage.reset()
        } else {
            lastGoodMainImage.set(mainImage.localMatrix)
            lastGoodMainImage.preConcat(mainImage.editorMatrix)
        }

        lastGoodUserCrop.set(userCrop.localMatrix)
        lastGoodUserCrop.preConcat(userCrop.editorMatrix)
    }

    fun restore(mainImage: EditorElement?, cropEditorElement: EditorElement, invalidate: Runnable?) {
        if (mainImage != null) {
            mainImage.animateLocalTo(lastGoodMainImage, invalidate)
        }
        cropEditorElement.animateLocalTo(lastGoodUserCrop, invalidate)
    }

    fun getLastKnownGoodMainImageMatrix(): Matrix = Matrix(lastGoodMainImage)
}
