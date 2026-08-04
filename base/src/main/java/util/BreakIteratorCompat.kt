package util

import android.os.Build

abstract class BreakIteratorCompat : Iterable<CharSequence> {

    private var charSequence: CharSequence? = null

    abstract fun first(): Int

    abstract fun next(): Int

    open fun setText(charSequence: CharSequence) {
        this.charSequence = charSequence
    }

    fun countBreaks(): Int {
        var breakCount = 0

        first()

        while (next() != DONE) {
            breakCount++
        }

        return breakCount
    }

    override fun iterator(): Iterator<CharSequence> = object : Iterator<CharSequence> {
        private var index1 = this@BreakIteratorCompat.first()
        private var index2 = this@BreakIteratorCompat.next()

        override fun hasNext(): Boolean = index2 != DONE

        override fun next(): CharSequence {
            val c = if (index2 != DONE) charSequence!!.subSequence(index1, index2) else ""

            index1 = index2
            index2 = this@BreakIteratorCompat.next()

            return c
        }
    }

    /**
     * Take [atMost] graphemes from the start of string.
     */
    fun take(atMost: Int): CharSequence {
        if (atMost <= 0) return ""

        val stringBuilder = StringBuilder(charSequence!!.length)
        var count = 0

        for (grapheme in this) {
            stringBuilder.append(grapheme)

            count++

            if (count >= atMost) break
        }

        return stringBuilder.toString()
    }

    /**
     * The BreakIteratorCompat implementation, delegating to `android.icu.text.BreakIterator`.
     * Handles grapheme clusters correctly. Sole implementation now (minSdk=26 always
     * satisfies its API-24 requirement); do not re-add an SDK_INT/@RequiresApi guard.
     */
    private class AndroidIcuBreakIterator : BreakIteratorCompat() {
        private val breakIterator = android.icu.text.BreakIterator.getCharacterInstance()

        override fun first(): Int = breakIterator.first()

        override fun next(): Int = breakIterator.next()

        override fun setText(charSequence: CharSequence) {
            super.setText(charSequence)
            if (Build.VERSION.SDK_INT >= 29) {
                breakIterator.setText(charSequence)
            } else {
                breakIterator.setText(charSequence.toString())
            }
        }
    }

    companion object {
        const val DONE = -1

        @JvmStatic
        fun getInstance(): BreakIteratorCompat = AndroidIcuBreakIterator()
    }
}
