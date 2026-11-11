package util




class CharacterIterable(private val string: String) : Iterable<String> {

    override fun iterator(): Iterator<String> = CharacterIterator()

    private inner class CharacterIterator : Iterator<String> {
        
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
