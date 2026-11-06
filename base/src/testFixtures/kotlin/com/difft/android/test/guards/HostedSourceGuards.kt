package com.difft.android.test.guards

import java.io.File

/**
 * Source-text rules for pages hosted in IndexActivity's detail stacks, shared by the hosted-page
 * guard (chat) and the window-background guard (app). Each rule is a pure function over source
 * text so its fixtures can prove it rejects what it should. Full-line `//` comments are ignored.
 */
object HostedSourceGuards {

    /** Main source roots searched for destinations and their pages. */
    val MAIN_ROOTS = listOf("app/src/main/java", "chat/src/main/java", "login/src/main/java")

    /** Same-package files that are part of a page when its fragment file references them. */
    private val CONTENT_SUFFIXES = listOf("Content.kt", "Screen.kt", "Fragment.kt", "Binder.kt", "Rows.kt", "Dialogs.kt", "View.kt")

    fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: break
        }
        return dir
    }

    fun stripLineComments(source: String): String =
        source.lines().joinToString("\n") { if (it.trimStart().startsWith("//")) "" else it }

    fun lineOf(source: String, index: Int): Int = source.substring(0, index).count { it == '\n' } + 1

    // ------------------------------------------------------------------ derivation

    /** Simple names of the fragments that `: DetailDestination` classes in [source] build. */
    /**
     * Fails when a destination's `newFragment()` is written in a form not understood here, so a
     * page cannot silently drop out of the derived hosted set. Understood: `= XFragment.newInstance(…)`,
     * `= XFragment(…)` and `{ return … }` with either, also across line breaks.
     */
    fun destinationFragments(source: String): List<String> {
        val text = stripLineComments(source)
        if (!Regex(""":\s*DetailDestination\b""").containsMatchIn(text)) return emptyList()
        val declared = Regex("""override fun newFragment\(""").findAll(text).count()
        val names = Regex("""override fun newFragment\(\)\s*(?::\s*\w+\??)?\s*(?:=|\{\s*return)\s*(\w+)(?:\.\w+)?\(""")
            .findAll(text).map { it.groupValues[1] }.toList()
        check(names.size == declared) {
            "newFragment() of a DetailDestination not understood ($declared declared, ${names.size} read): " +
                "write `= XFragment.newInstance(...)`, `= XFragment(...)` or `{ return ... }`, or teach the guard"
        }
        return names
    }

    /** Simple names of the shell Activities named by `shellIntent` in [source]. */
    fun destinationShells(source: String): List<String> {
        if (!Regex(""":\s*DetailDestination\b""").containsMatchIn(source)) return emptyList()
        return Regex("""override fun shellIntent\([^)]*\)[^=]*=\s*(?:Intent\(\s*\w+\s*,\s*(\w+)::class\.java|(\w+)\.\w+\()""")
            .findAll(source).map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }.toList()
    }

    /** Names of non-private top-level declarations in [source]. */
    fun topLevelDeclarations(source: String): Set<String> =
        Regex(
            """^(?:(?:internal|public|data|sealed|enum|abstract|open|inline|value|annotation|const|suspend|operator|infix|fun)\s+)*""" +
                """(?:class|object|interface|fun|val|var)\s+(?:<[^>]+>\s+)?(?:[\w.]+(?:<[^>]*>)?\??\.)?(\w+)""",
            RegexOption.MULTILINE,
        ).findAll(source).map { it.groupValues[1] }.toSet()

    /** The files under [MAIN_ROOTS] of [root]. */
    fun mainSources(root: File): List<File> = MAIN_ROOTS.flatMap { dir ->
        File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Destination fragment files: the file of each fragment a destination's `newFragment()` builds. */
    fun destinationFragmentFiles(root: File): List<File> {
        val sources = mainSources(root)
        val names = sources.flatMap { destinationFragments(it.readText()) }.distinct()
        check(names.isNotEmpty()) { "no DetailDestination found: the derivation no longer matches the sources" }
        return names.map { name ->
            sources.singleOrNull { name in topLevelDeclarations(it.readText()) }
                ?: error("$name declaration not found uniquely in main sources")
        }
    }

    /** Destination pages and the Me roots built directly by MeFragment. */
    fun hostedFragmentFiles(root: File): List<File> {
        val sources = mainSources(root)
        val meSource = sources.single { it.name == "MeFragment.kt" }.readText()
        val meNames = Regex("""navigateToDetailOrActivity\(\s*(\w+)\.newInstance""")
            .findAll(meSource).map { it.groupValues[1] }.distinct().toList()
        check(meNames.isNotEmpty()) { "Me root derivation found no pages" }
        val roots = meNames.map { name ->
            sources.singleOrNull { name in topLevelDeclarations(it.readText()) }
                ?: error("Me root $name not found uniquely")
        }
        return (destinationFragmentFiles(root) + roots).distinct()
    }

    /** Hosted fragment files plus the same-package content files they reference. */
    fun derivedHostedFiles(root: File): Set<File> {
        val pages = hostedFragmentFiles(root)
        val companions = pages.flatMap { page ->
            val pageSource = stripLineComments(page.readText())
            page.parentFile.listFiles().orEmpty()
                .filter { it.isFile && it != page && CONTENT_SUFFIXES.any { suffix -> it.name.endsWith(suffix) } }
                .filter { file ->
                    topLevelDeclarations(file.readText()).any { Regex("""\b$it\b""").containsMatchIn(pageSource) }
                }
        }
        return (pages + companions).toSet()
    }

    /** Shell Activities named by any destination. */
    fun destinationShellNames(root: File): Set<String> =
        mainSources(root).flatMap { destinationShells(it.readText()) }.toSet()

    // ------------------------------------------------------------------ rules

    private val FORBIDDEN = listOf(
        Regex("""requireActivity\(\)\.intent\b""") to "reads the Activity's intent (use fragment arguments)",
        Regex("""\bactivity\?\.intent\b""") to "reads the Activity's intent (use fragment arguments)",
        Regex("""\bsystemBarsPadding\(\)""") to "pads system bars (the container does; use detailPageInsets)",
        Regex("""\bstatusBarsPadding\(\)""") to "pads the status bar (the container does)",
        Regex("""\bstartActivityForResult\(""") to "Activity result on the host (re-read on resume)",
        Regex("""\bsetResult\(""") to "Activity result on the host (pop / re-read on resume)",
        Regex("""FragmentStateAdapter\(\s*requireActivity\(\)""") to "pager owned by the Activity (pass the fragment)",
        Regex("""FragmentStateAdapter\(\s*activity\b""") to "pager owned by the Activity (pass the fragment)",
    )

    /** Activity-owned APIs a hosted page must not use. */
    fun forbiddenCalls(source: String): List<String> {
        val text = stripLineComments(source)
        return FORBIDDEN.flatMap { (regex, why) ->
            regex.findAll(text).map { "line ${lineOf(text, it.range.first)}: ${it.value} - $why" }.toList()
        }
    }

    /** Direct starts of a destination's shell Activity (use the destination instead). */
    fun shellStarts(source: String, shells: Set<String>): List<String> {
        if (shells.isEmpty()) return emptyList()
        val text = stripLineComments(source)
        val regex = Regex("""\b(${shells.joinToString("|")})\.(start|startActivity)\(""")
        return regex.findAll(text).map { "line ${lineOf(text, it.range.first)}: ${it.value}" }.toList()
    }

    /** Navigation pushes that do not pass the asking page first. */
    fun pushesWithoutFrom(source: String): List<String> {
        val text = stripLineComments(source)
        return Regex("""(?:[Nn]avigation\w*(?:\(\))?|[Nn]avigation)\??\.push\(""").findAll(text).mapNotNull { match ->
            val args = callArguments(text, match.range.last) ?: return@mapNotNull null
            if (args.size >= 2) null else "line ${lineOf(text, match.range.first)}: push without from"
        }.toList()
    }

    /** A page whose view is Compose (`onCreateView` builds a ComposeView) must consume the container's insets. */
    fun composeWithoutContainerInsets(source: String): Boolean {
        val text = stripLineComments(source)
        val start = text.indexOf("fun onCreateView(")
        if (start < 0) return false
        val end = Regex("""\n\s*(?:(?:override|private|internal|protected)\s+)*fun\s""").find(text, start + 1)?.range?.first ?: text.length
        if (!text.substring(start, end).contains("ComposeView(")) return false
        return !text.contains("detailPageInsets(") && !text.contains("consumeContainerInsets(")
    }

    /** Global, per-claim-blind releases of the shared wait dialog. */
    fun noArgDismissWaits(source: String): List<String> {
        val text = stripLineComments(source)
        return Regex("""\bdismissWait\(\)""").findAll(text).map { "line ${lineOf(text, it.range.first)}: dismissWait()" }.toList()
    }

    /** Calls of [functions] that do not pass `wait =` (declarations excluded). */
    fun callsWithoutWait(source: String, functions: List<String>): List<String> {
        val text = stripLineComments(source)
        return Regex("""(?<!fun )\b(${functions.joinToString("|")})\(""").findAll(text).mapNotNull { match ->
            val args = callArguments(text, match.range.last) ?: return@mapNotNull null
            if (args.any { Regex("""^\s*wait\s*=""").containsMatchIn(it) }) null
            else "line ${lineOf(text, match.range.first)}: ${match.groupValues[1]} without wait ="
        }.toList()
    }

    /**
     * `DifftTheme` calls on a hosted page must not write the home screen's window background:
     * each passes `applyWindowBackground =` the literal `false` or an expression with `ownsWindow()`.
     */
    fun windowBackgroundViolations(source: String): List<String> {
        val text = stripLineComments(source)
        return Regex("""\bDifftTheme\s*([({])""").findAll(text).mapNotNull { match ->
            if (inPreview(text, match.range.first)) return@mapNotNull null
            val where = "line ${lineOf(text, match.range.first)}"
            if (match.groupValues[1] == "{") return@mapNotNull "$where: DifftTheme without applyWindowBackground"
            val args = callArguments(text, match.groups[1]!!.range.first) ?: return@mapNotNull null
            val value = args.firstNotNullOfOrNull { Regex("""^\s*applyWindowBackground\s*=\s*(.+?)\s*$""", RegexOption.DOT_MATCHES_ALL).find(it) }
                ?.groupValues?.get(1)
            when {
                value == null -> "$where: DifftTheme without applyWindowBackground"
                ownsWindowOnly(value) -> null
                else -> "$where: applyWindowBackground = $value (use false or ownsWindow())"
            }
        }.toList()
    }

    /** Whether [index] is inside a function annotated `@Preview` (never run on a device page). */
    private fun inPreview(text: String, index: Int): Boolean {
        val declaration = Regex("""(?m)^\s*(?:(?:private|internal|public)\s+)?fun\s+\w+""").findAll(text.substring(0, index)).lastOrNull()
            ?: return false
        val annotations = text.substring(0, declaration.range.first).trimEnd().lines().asReversed()
            .takeWhile { it.trim().startsWith("@") }
        return annotations.any { it.trim().startsWith("@Preview") }
    }

    /**
     * `false`, `ownsWindow()`, or a conjunction with `ownsWindow()` (`!isPopupMode && ownsWindow()`):
     * true only when the page owns its window. An inverse (`!ownsWindow()`) or a disjunction is not.
     */
    private fun ownsWindowOnly(value: String): Boolean {
        val v = value.trim()
        if (v == "false" || v == "ownsWindow()") return true
        if (v.contains("||")) return false
        return Regex("""&&\s*ownsWindow\(\)$""").containsMatchIn(v) || Regex("""^ownsWindow\(\)\s*&&""").containsMatchIn(v)
    }

    /** Top-level arguments of the call whose `(` is at [openIndex]; null when unbalanced. */
    fun callArguments(text: String, openIndex: Int): List<String>? {
        if (text.getOrNull(openIndex) != '(') return null
        var depth = 0
        val args = mutableListOf<String>()
        val current = StringBuilder()
        for (i in openIndex until text.length) {
            val c = text[i]
            when (c) {
                '(', '[', '{' -> {
                    depth++
                    if (depth > 1) current.append(c)
                }
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) {
                        if (current.isNotBlank()) args += current.toString()
                        return args
                    }
                    current.append(c)
                }
                ',' -> if (depth == 1) {
                    args += current.toString()
                    current.clear()
                } else {
                    current.append(c)
                }
                else -> current.append(c)
            }
        }
        return null
    }
}
