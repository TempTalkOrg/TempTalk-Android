package util







fun String.asListContains(item: String): Boolean {
  val items: List<String> = this
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toList()

  val exactMatches = items.filter { it.last() != '*' }
  val prefixMatches = items.filter { it.last() == '*' }

  return exactMatches.contains(item) ||
    prefixMatches
      .map { it.substring(0, it.length - 1) }
      .any { item.startsWith(it) }
}
