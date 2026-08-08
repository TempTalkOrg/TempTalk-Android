package com.difft.android.imageeditor.core.model

/**
 * Flags for an [EditorElement].
 *
 * Values you set are not persisted unless you call [persist].
 *
 * This allows temporary state for editing and an easy way to revert to the persisted state via [reset].
 */
class EditorFlags(flags: Int) {

    private var flags: Int = flags
    private var markedFlags = 0
    private var persistedFlags: Int = flags

    constructor() : this(ASPECT_LOCK or SELECTABLE or VISIBLE or CHILDREN_VISIBLE or EDITABLE)

    fun setRotateLocked(rotateLocked: Boolean): EditorFlags {
        setFlag(ROTATE_LOCK, rotateLocked)
        return this
    }

    fun isRotateLocked(): Boolean = isFlagSet(ROTATE_LOCK)

    fun setAspectLocked(aspectLocked: Boolean): EditorFlags {
        setFlag(ASPECT_LOCK, aspectLocked)
        return this
    }

    fun isAspectLocked(): Boolean = isFlagSet(ASPECT_LOCK)

    fun setSelectable(selectable: Boolean): EditorFlags {
        setFlag(SELECTABLE, selectable)
        return this
    }

    fun isSelectable(): Boolean = isFlagSet(SELECTABLE)

    fun setEditable(canEdit: Boolean): EditorFlags {
        setFlag(EDITABLE, canEdit)
        return this
    }

    fun isEditable(): Boolean = isFlagSet(EDITABLE)

    fun setVisible(visible: Boolean): EditorFlags {
        setFlag(VISIBLE, visible)
        return this
    }

    fun isVisible(): Boolean = isFlagSet(VISIBLE)

    fun setChildrenVisible(childrenVisible: Boolean): EditorFlags {
        setFlag(CHILDREN_VISIBLE, childrenVisible)
        return this
    }

    fun isChildrenVisible(): Boolean = isFlagSet(CHILDREN_VISIBLE)

    private fun setFlag(flag: Int, set: Boolean) {
        flags = if (set) {
            flags or flag
        } else {
            flags and flag.inv()
        }
    }

    private fun isFlagSet(flag: Int): Boolean = (flags and flag) != 0

    internal fun asInt(): Int = persistedFlags

    internal fun getCurrentState(): Int = flags

    fun persist() {
        persistedFlags = flags
    }

    fun reset() {
        restoreState(persistedFlags)
    }

    internal fun restoreState(flags: Int) {
        this.flags = flags
    }

    internal fun mark() {
        markedFlags = flags
    }

    internal fun restore() {
        flags = markedFlags
    }

    fun set(from: EditorFlags) {
        this.persistedFlags = from.persistedFlags
        this.flags = from.flags
    }

    companion object {
        private const val ASPECT_LOCK = 1
        private const val ROTATE_LOCK = 2
        private const val SELECTABLE = 4
        private const val VISIBLE = 8
        private const val CHILDREN_VISIBLE = 16
        private const val EDITABLE = 32
    }
}
