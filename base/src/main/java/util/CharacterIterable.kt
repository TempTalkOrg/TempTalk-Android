package util

/**
 * Iterates over a string treating a surrogate pair and a grapheme cluster a single character.
 */
class CharacterIterable(private val string: String) : Iterable<String> {

    override fun iterator(): Iterator<String> = CharacterIterator()

    private inner class CharacterIterator : Iterator<String> {
        // Local grapheme breaker; distinct from the top-level util.BreakIteratorCompat.
        private val breakIterator: GraphemeBreaker = AndroidIcuGraphemeBreaker(string)

        private var lastIndex = UNINITIALIZED

        override fun hasNext(): Boolean {
            if (lastIndex == UNINITIALIZED) {
                lastIndex = breakIterator.first()
            }
            return !breakIterator.isDone(lastIndex)
        }

        override fun next(): String {
            val firstIndex = lastIndex
            lastIndex = breakIterator.next()
            return string.substring(firstIndex, lastIndex)
        }
    }

    private interface GraphemeBreaker {
        fun first(): Int

        fun next(): Int

        fun isDone(index: Int): Boolean
    }

    /**
     * The GraphemeBreaker implementation, delegating to `android.icu.text.BreakIterator`.
     * Handles grapheme clusters correctly. Sole implementation now (minSdk=26 always
     * satisfies its API-24 requirement); do not re-add an SDK_INT/@RequiresApi guard.
     */
    private class AndroidIcuGraphemeBreaker(string: String) : GraphemeBreaker {
        private val breakIterator = android.icu.text.BreakIterator.getCharacterInstance()

        init {
            breakIterator.setText(string)
        }

        override fun first(): Int = breakIterator.first()

        override fun next(): Int = breakIterator.next()

        override fun isDone(index: Int): Boolean = index == android.icu.text.BreakIterator.DONE
    }

    companion object {
        private const val UNINITIALIZED = -2
    }
}
