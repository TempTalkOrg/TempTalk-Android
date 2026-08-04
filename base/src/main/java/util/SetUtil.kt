package util

object SetUtil {

    @JvmStatic
    fun <E> intersection(a: Collection<E>, b: Collection<E>): Set<E> {
        val intersection = LinkedHashSet(a)
        intersection.retainAll(b.toSet())
        return intersection
    }

    @JvmStatic
    fun <E> difference(a: Collection<E>, b: Collection<E>): Set<E> {
        val difference = LinkedHashSet(a)
        difference.removeAll(b.toSet())
        return difference
    }

    @JvmStatic
    fun <E> union(a: Set<E>, b: Set<E>): Set<E> {
        val result = LinkedHashSet(a)
        result.addAll(b)
        return result
    }

    @JvmStatic
    fun <E> newHashSet(vararg elements: E): HashSet<E> {
        return HashSet(elements.asList())
    }
}
