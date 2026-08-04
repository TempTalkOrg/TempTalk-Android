package com.difft.android.imageeditor.core.model

import android.graphics.Matrix
import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import com.difft.android.imageeditor.core.MatrixUtils
import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.RendererContext
import java.util.UUID

/**
 * An image consists of a tree of [EditorElement]s.
 *
 * Each element has some persisted state:
 * - An optional [Renderer] so that it can draw itself.
 * - A list of child elements that make the tree possible.
 * - Its own transformation matrix, which applies to itself and all its children.
 * - A set of flags controlling visibility, selectablity etc.
 *
 * Then some temporary state.
 * - A editor matrix for displaying as yet uncommitted edits.
 * - An animation matrix for animating from one matrix to another.
 * - Deleted children to allow them to fade out on delete.
 * - Temporary flags, for temporary visibility, selectablity etc.
 */
@Parcelize
@TypeParceler<UUID, UuidParceler>()
@TypeParceler<EditorFlags, FlagsParceler>()
@TypeParceler<Matrix, MatrixParceler>()
class EditorElement private constructor(
    val id: UUID,
    val flags: EditorFlags,
    val localMatrix: Matrix,
    val renderer: Renderer?,
    val zOrder: Int,
    private val children: MutableList<EditorElement>
) : Parcelable {

    @IgnoredOnParcel
    val editorMatrix = Matrix()

    @IgnoredOnParcel
    private val temp = Matrix()

    @IgnoredOnParcel
    private val tempMatrix = Matrix()

    @IgnoredOnParcel
    private val deletedChildren: MutableList<EditorElement> = ArrayList()

    @IgnoredOnParcel
    private var animationMatrix: AnimationMatrix = AnimationMatrix.NULL

    @IgnoredOnParcel
    private var alphaAnimation: AlphaAnimation = AlphaAnimation.NULL_1

    constructor(renderer: Renderer?) : this(renderer, 0)

    constructor(renderer: Renderer?, zOrder: Int) : this(
        UUID.randomUUID(),
        EditorFlags(),
        Matrix(),
        renderer,
        zOrder,
        ArrayList()
    )

    /**
     * Iff Visible,
     * Renders tree with the following localMatrix:
     *
     * viewModelMatrix * localMatrix * editorMatrix * animationMatrix
     *
     * Child nodes are supplied with a viewModelMatrix' = viewModelMatrix * localMatrix * editorMatrix * animationMatrix
     *
     * @param rendererContext Canvas to draw on to.
     */
    fun draw(rendererContext: RendererContext) {
        if (!flags.isVisible() && !flags.isChildrenVisible()) return

        rendererContext.save()

        rendererContext.canvasMatrix.concat(localMatrix)

        if (rendererContext.isEditing()) {
            rendererContext.canvasMatrix.concat(editorMatrix)
            animationMatrix.preConcatValueTo(rendererContext.canvasMatrix)
        }

        if (flags.isVisible()) {
            val alpha = alphaAnimation.getValue()
            if (alpha > 0) {
                rendererContext.setFade(alpha)
                rendererContext.children = children
                drawSelf(rendererContext)
                rendererContext.setFade(1f)
            }
        }

        if (flags.isChildrenVisible()) {
            drawChildren(children, rendererContext)
            drawChildren(deletedChildren, rendererContext)
        }

        rendererContext.restore()
    }

    private fun drawSelf(rendererContext: RendererContext) {
        renderer?.render(rendererContext)
    }

    private fun drawChildren(children: List<EditorElement>, rendererContext: RendererContext) {
        for (element in children) {
            if (element.zOrder >= 0) {
                element.draw(rendererContext)
            }
        }
    }

    fun addElement(element: EditorElement) {
        children.add(element)
        children.sortWith(Z_ORDER_COMPARATOR)
    }

    fun findElement(toFind: EditorElement, viewMatrix: Matrix, outInverseModelMatrix: Matrix): EditorElement? =
        findElement(viewMatrix, outInverseModelMatrix) { element, _ -> toFind === element }

    fun findElementAt(x: Float, y: Float, viewModelMatrix: Matrix, outInverseModelMatrix: Matrix): EditorElement? {
        val dst = FloatArray(2)
        val src = floatArrayOf(x, y)

        return findElement(viewModelMatrix, outInverseModelMatrix) { element, inverseMatrix ->
            val renderer = element.renderer
            if (renderer == null) {
                false
            } else {
                inverseMatrix.mapPoints(dst, src)
                element.flags.isSelectable() && renderer.hitTest(dst[0], dst[1])
            }
        }
    }

    fun findElement(viewModelMatrix: Matrix, outInverseModelMatrix: Matrix, predicate: FindElementPredicate): EditorElement? {
        temp.set(viewModelMatrix)

        temp.preConcat(localMatrix)
        temp.preConcat(editorMatrix)

        if (temp.invert(tempMatrix)) {

            for (i in children.size - 1 downTo 0) {
                val elementAt = children[i].findElement(temp, outInverseModelMatrix, predicate)
                if (elementAt != null) {
                    return elementAt
                }
            }

            if (predicate.test(this, tempMatrix)) {
                outInverseModelMatrix.set(tempMatrix)
                return this
            }
        }

        return null
    }

    fun getChildCount(): Int = children.size

    fun getChild(i: Int): EditorElement = children[i]

    fun forAllInTree(function: PerElementFunction) {
        function.apply(this)
        for (child in children) {
            child.forAllInTree(function)
        }
    }

    fun findParent(editorElement: EditorElement): EditorElement? {
        for (child in children) {
            if (child === editorElement) {
                return this
            } else {
                val element = child.findParent(editorElement)
                if (element != null) {
                    return element
                }
            }
        }
        return null
    }

    fun findElementWithId(id: UUID): EditorElement? {
        for (child in children) {
            if (id == child.id) {
                return child
            } else {
                val element = child.findElementWithId(id)
                if (element != null) {
                    return element
                }
            }
        }
        return null
    }

    fun deleteChild(editorElement: EditorElement, invalidate: Runnable?) {
        val iterator = children.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() === editorElement) {
                iterator.remove()
                addDeletedChildFadingOut(editorElement, invalidate)
            }
        }
    }

    fun addDeletedChildFadingOut(fromElement: EditorElement, invalidate: Runnable?) {
        deletedChildren.add(fromElement)
        fromElement.animateFadeOut(invalidate)
    }

    fun animateFadeOut(invalidate: Runnable?) {
        alphaAnimation = AlphaAnimation.animate(1f, 0f, invalidate)
    }

    fun animateFadeIn(invalidate: Runnable?) {
        alphaAnimation = AlphaAnimation.animate(0f, 1f, invalidate)
    }

    fun animatePartialFadeOut(invalidate: Runnable?) {
        alphaAnimation = AlphaAnimation.animate(alphaAnimation.getValue(), 0.5f, invalidate)
    }

    fun animatePartialFadeIn(invalidate: Runnable?) {
        alphaAnimation = AlphaAnimation.animate(alphaAnimation.getValue(), 1f, invalidate)
    }

    fun parentOf(element: EditorElement): EditorElement? {
        if (children.contains(element)) {
            return this
        }
        for (child in children) {
            val parent = child.parentOf(element)
            if (parent != null) {
                return parent
            }
        }
        return null
    }

    fun singleScalePulse(invalidate: Runnable?) {
        val scale = Matrix()
        scale.setScale(1.2f, 1.2f)

        animationMatrix = AnimationMatrix.singlePulse(scale, invalidate)
    }

    fun deleteAllChildren() {
        children.clear()
    }

    fun getLocalRotationAngle(): Float = MatrixUtils.getRotationAngle(localMatrix)

    fun getLocalScaleX(): Float = MatrixUtils.getScaleX(localMatrix)

    fun interface PerElementFunction {
        fun apply(element: EditorElement)
    }

    fun interface FindElementPredicate {
        fun test(element: EditorElement, inverseMatrix: Matrix): Boolean
    }

    fun commitEditorMatrix() {
        if (flags.isEditable()) {
            localMatrix.preConcat(editorMatrix)
            editorMatrix.reset()
        } else {
            rollbackEditorMatrix(null)
        }
    }

    fun rollbackEditorMatrix(invalidate: Runnable?) {
        animateEditorTo(Matrix(), invalidate)
    }

    fun buildMap(map: MutableMap<UUID, EditorElement>) {
        map[id] = this
        for (child in children) {
            child.buildMap(map)
        }
    }

    fun animateFrom(oldMatrix: Matrix, invalidate: Runnable?) {
        val oldMatrixCopy = Matrix(oldMatrix)
        animationMatrix.stop()
        animationMatrix.preConcatValueTo(oldMatrixCopy)
        animationMatrix = AnimationMatrix.animate(oldMatrixCopy, localMatrix, invalidate)
    }

    fun animateEditorTo(newEditorMatrix: Matrix, invalidate: Runnable?) {
        setMatrixWithAnimation(editorMatrix, newEditorMatrix, invalidate)
    }

    fun animateLocalTo(newLocalMatrix: Matrix, invalidate: Runnable?) {
        setMatrixWithAnimation(localMatrix, newLocalMatrix, invalidate)
    }

    /**
     * @param destination Matrix to change
     * @param source      Matrix value to set
     * @param invalidate  Callback to allow animation
     */
    private fun setMatrixWithAnimation(destination: Matrix, source: Matrix, invalidate: Runnable?) {
        val old = Matrix(destination)
        animationMatrix.stop()
        animationMatrix.preConcatValueTo(old)
        destination.set(source)
        animationMatrix = AnimationMatrix.animate(old, destination, invalidate)
    }

    fun getLocalMatrixAnimating(): Matrix {
        val matrix = Matrix(localMatrix)
        animationMatrix.preConcatValueTo(matrix)
        return matrix
    }

    fun stopAnimation() {
        animationMatrix.stop()
    }

    companion object {
        private val Z_ORDER_COMPARATOR: Comparator<EditorElement> =
            Comparator { e1, e2 -> e1.zOrder.compareTo(e2.zOrder) }
    }
}
