package com.difft.android.architecture

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Structural guards for the search/clearable-input unification: 10 search boxes plus 6 form
 * inputs across 5 modules migrated from EditText+ImageButton pairs (and the retired
 * EditableTextView/RuleEditText) to the shared DifftClearableInputView/DifftSearchInputView.
 *
 * Why source-text guards: 9 of the migrated hosts are @AndroidEntryPoint and this repository
 * has no Hilt test infrastructure, so the highest-risk regression — a host forgetting to wire
 * `onClear` (which compiles fine and merely leaves the list stale after ✕) — can only be pinned
 * statically. These guards prove "wired", not "wired correctly"; correctness is covered by the
 * per-call-site manual QA matrix in the PR description.
 *
 * Known limitations (documented, not hidden): regex over source text, not an AST; comment lines
 * are stripped before matching; assumes the test JVM's working directory resolves inside the
 * repo tree. A candidate host that cannot be located fails loudly instead of passing vacuously.
 */
class SearchInputMigrationGuardTest {

    // Reverted call site: the meeting-invite sheet keeps its legacy EditText implementation.
    // Its host (LCallActivity) is a resizable/PiP window whose multi-pass sizing makes an
    // AbstractComposeView-hosted field visibly unstable during the dialog's entrance; only the
    // visuals were aligned. Re-unification is a tracked follow-up.
    private val revertedCallSheet = setOf("MeetingInviteBottomSheetFragment.kt")

    // D1: no findViewById with the LEGACY types/ids may survive — after the id rename these are
    // either compile errors (button_clear id deleted) or a null-returning lookup
    // (edittext_search_input still exists in two out-of-scope pseudo-search TextViews).
    @Test
    fun `no legacy findViewById of search EditText or clear ImageButton remains`() {
        val pattern = Regex(
            """findViewById\s*<\s*AppCompat(EditText|ImageButton)\s*>\s*\(\s*R\.id\.(edittext_search_input|button_clear)"""
        )
        val hits = scanKotlin { file, line -> file.name !in revertedCallSheet && pattern.containsMatchIn(line) }
        assertTrue(hits.isEmpty(), "Legacy findViewById survived the migration:\n${hits.joinToString("\n")}")
    }

    // D2: every layout hosting the shared component has a Kotlin host that assigns onClear —
    // a missed assignment compiles (default {}) and silently leaves stale results after ✕.
    // Candidate list is DERIVED from the layouts, never hardcoded.
    @Test
    fun `every DifftClearableInputView host wires onClear`() {
        val componentLayouts = layoutFiles().filter { f ->
            val text = f.readText()
            text.contains("DifftSearchInputView") || text.contains("DifftClearableInputView")
        }
        assertTrue(componentLayouts.isNotEmpty(), "No component layouts found — guard is vacuous")

        // Inputs that deliberately need no onClear side effect (pure local text, read on demand,
        // zero TextWatcher today). Layout-level allowlist, each entry justified:
        //  - activity_enter_code: invite code read once on Add; no watcher existed
        val noSideEffectLayouts = setOf(
            "activity_enter_code.xml",
        )

        val violations = componentLayouts.mapNotNull { layout ->
            if (layout.name in noSideEffectLayouts) return@mapNotNull null
            val module = layout.path.substringBefore("/src/")
            val hosts = kotlinFiles().filter { k ->
                k.path.startsWith("$module/src/") && stripLineComments(k.readText())
                    .let { src -> src.contains(layout.nameWithoutExtension.toBindingRef()) || src.contains("R.layout.${layout.nameWithoutExtension}") }
            }
            if (hosts.isEmpty()) return@mapNotNull "${layout.path}: no Kotlin host found (loud failure, not a skip)"
            val wired = hosts.any { stripLineComments(it.readText()).contains(Regex("""onClear\s*=""")) }
            if (wired) null else "${layout.path}: host(s) ${hosts.map { it.name }} never assign onClear"
        }
        assertTrue(violations.isEmpty(), "onClear wiring missing:\n${violations.joinToString("\n")}")
    }

    // D3: migration completeness — zero in-scope references to the legacy binding fields,
    // helpers, or raw ids may remain in Kotlin.
    @Test
    fun `no legacy search-clear reference survives in scope`() {
        val pattern = Regex(
            """\.buttonClear\b|\bresetButtonClear\s*\(|edittextSearchInput\s*\.\s*(setText|text\b|addTextChangedListener|setSelection)|findViewById\s*<[^>]*>\s*\(\s*R\.id\.(button_clear|edittext_search_input)"""
        )
        // Out-of-scope pseudo-search entry points that must SURVIVE untouched (they are
        // AppCompatTextViews acting as tap-through search entries, per the task's exclusion list).
        val allowlist = setOf("SearchInputFragment.kt", "GroupInfoActivity.kt") + revertedCallSheet
        val hits = scanKotlin { file, line ->
            file.name !in allowlist && pattern.containsMatchIn(line)
        }
        assertTrue(hits.isEmpty(), "Legacy references survived:\n${hits.joinToString("\n")}")
    }

    // D4: autofocus preservation — each of the four layouts that declared <requestFocus/> must
    // either still declare it (not yet migrated) or carry dsi_autoFocus="true". Both absent =
    // a lost "keyboard opens on entry" behavior with zero compile signal.
    @Test
    fun `autofocus survives on all four requestFocus layouts`() {
        val four = listOf(
            "app/src/main/res/layout/activity_search.xml",
            "chat/src/main/res/layout/activity_search_message.xml",
            "chat/src/main/res/layout/activity_search_group_member.xml",
            "chat/src/main/res/layout/chat_layout_forward_select_chat.xml",
        )
        val violations = four.mapNotNull { rel ->
            val f = File(projectRoot(), rel)
            if (!f.exists()) return@mapNotNull "$rel: file missing"
            val text = f.readText()
            if (text.contains("<requestFocus") || text.contains("""dsi_autoFocus="true"""")) null
            else "$rel: neither <requestFocus/> nor dsi_autoFocus=\"true\" present"
        }
        assertTrue(violations.isEmpty(), "Autofocus lost:\n${violations.joinToString("\n")}")
    }

    // D5/D6: retirement completeness — the retired widget family and its attributes must be gone.
    @Test
    fun `EditableTextView RuleEditText family is fully retired`() {
        val pattern = Regex(
            """EditableTextView|RuleEditText|setTextWatchCallback|app:clearable|app:rule=|account_input_clear_icon|circled_close_f"""
        )
        val hits = mutableListOf<String>()
        (kotlinFiles() + layoutFiles() + valuesFiles()).forEach { f ->
            stripLineComments(f.readText()).lineSequence().forEachIndexed { i, line ->
                if (pattern.containsMatchIn(line)) hits.add("${f.path}:${i + 1}: ${line.trim()}")
            }
        }
        assertTrue(hits.isEmpty(), "Retired symbols survived:\n${hits.joinToString("\n")}")
    }

    // ---------------- helpers ----------------

    private fun String.toBindingRef(): String =
        // activity_search -> ActivitySearchBinding
        "Binding".let { suffix ->
            split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } } + suffix
        }

    private fun stripLineComments(src: String): String =
        src.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            // crude but adequate: strip XML comments and Kotlin line comments
            when {
                idx >= 0 && !line.substring(0, idx).contains("http") -> line.substring(0, idx)
                line.trimStart().startsWith("<!--") -> ""
                else -> line
            }
        }

    private fun scanKotlin(match: (File, String) -> Boolean): List<String> {
        val hits = mutableListOf<String>()
        kotlinFiles().forEach { f ->
            stripLineComments(f.readText()).lineSequence().forEachIndexed { i, line ->
                if (match(f, line)) hits.add("${f.path}:${i + 1}: ${line.trim()}")
            }
        }
        return hits
    }

    private val moduleRoots = listOf("app", "chat", "base", "call", "login", "selector", "video")

    private fun kotlinFiles(): List<File> = moduleRoots.flatMap { m ->
        File(projectRoot(), "$m/src/main/java").takeIf { it.exists() }
            ?.walkTopDown()?.filter { it.extension == "kt" }?.toList() ?: emptyList()
    }

    private fun layoutFiles(): List<File> = moduleRoots.flatMap { m ->
        File(projectRoot(), "$m/src/main/res").takeIf { it.exists() }
            ?.walkTopDown()?.filter { it.extension == "xml" && it.parentFile.name.startsWith("layout") }?.toList()
            ?: emptyList()
    }

    private fun valuesFiles(): List<File> = moduleRoots.flatMap { m ->
        File(projectRoot(), "$m/src/main/res").takeIf { it.exists() }
            ?.walkTopDown()?.filter { it.extension == "xml" && it.parentFile.name.startsWith("values") }?.toList()
            ?: emptyList()
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: break
        }
        return dir
    }
}
