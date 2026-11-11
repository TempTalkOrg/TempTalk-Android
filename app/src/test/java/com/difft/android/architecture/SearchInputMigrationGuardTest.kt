package com.difft.android.architecture

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue
















class SearchInputMigrationGuardTest {

    
    
    
    
    private val revertedCallSheet = setOf("MeetingInviteBottomSheetFragment.kt")

    
    
    
    @Test
    fun `no legacy findViewById of search EditText or clear ImageButton remains`() {
        val pattern = Regex(
            """findViewById\s*<\s*AppCompat(EditText|ImageButton)\s*>\s*\(\s*R\.id\.(edittext_search_input|button_clear)"""
        )
        val hits = scanKotlin { file, line -> file.name !in revertedCallSheet && pattern.containsMatchIn(line) }
        assertTrue(hits.isEmpty(), "Legacy findViewById survived the migration:\n${hits.joinToString("\n")}")
    }

    
    
    
    @Test
    fun `every DifftClearableInputView host wires onClear`() {
        val componentLayouts = layoutFiles().filter { f ->
            val text = f.readText()
            text.contains("DifftSearchInputView") || text.contains("DifftClearableInputView")
        }
        assertTrue(componentLayouts.isNotEmpty(), "No component layouts found — guard is vacuous")

        
        
        
        
        val noSideEffectLayouts = setOf(
            "activity_enter_code.xml",
            "chat_layout_forward_dialog.xml",
        )

        val violations = componentLayouts.mapNotNull { layout ->
            if (layout.name in noSideEffectLayouts) return@mapNotNull null
            val module = layout.path.substringBefore("/src/")
            
            val names = listOf(layout.nameWithoutExtension) + layoutFiles()
                .filter { it.readText().contains("layout=\"@layout/${layout.nameWithoutExtension}\"") }
                .map { it.nameWithoutExtension }
            val hosts = kotlinFiles().filter { k ->
                k.path.startsWith("$module/src/") && stripLineComments(k.readText())
                    .let { src -> names.any { n -> src.contains(n.toBindingRef()) || src.contains("R.layout.$n") } }
            }
            if (hosts.isEmpty()) return@mapNotNull "${layout.path}: no Kotlin host found (loud failure, not a skip)"
            val wired = hosts.any { stripLineComments(it.readText()).contains(Regex("""onClear\s*=""")) }
            if (wired) null else "${layout.path}: host(s) ${hosts.map { it.name }} never assign onClear"
        }
        assertTrue(violations.isEmpty(), "onClear wiring missing:\n${violations.joinToString("\n")}")
    }

    
    
    @Test
    fun `no legacy search-clear reference survives in scope`() {
        val pattern = Regex(
            """\.buttonClear\b|\bresetButtonClear\s*\(|edittextSearchInput\s*\.\s*(setText|text\b|addTextChangedListener|setSelection)|findViewById\s*<[^>]*>\s*\(\s*R\.id\.(button_clear|edittext_search_input)"""
        )
        
        
        val allowlist = setOf("SearchInputFragment.kt", "GroupInfoFragment.kt") + revertedCallSheet
        val hits = scanKotlin { file, line ->
            file.name !in allowlist && pattern.containsMatchIn(line)
        }
        assertTrue(hits.isEmpty(), "Legacy references survived:\n${hits.joinToString("\n")}")
    }

    
    
    
    
    @Test
    fun `autofocus survives on all requestFocus layouts`() {
        val layouts = listOf(
            "app/src/main/res/layout/activity_search.xml",
            "chat/src/main/res/layout/activity_search_message.xml",
            "chat/src/main/res/layout/chat_layout_forward_select_chat.xml",
        )
        val violations = layouts.mapNotNull { rel ->
            val f = File(projectRoot(), rel)
            if (!f.exists()) return@mapNotNull "$rel: file missing"
            val text = f.readText()
            if (text.contains("<requestFocus") || text.contains("""dsi_autoFocus="true"""")) null
            else "$rel: neither <requestFocus/> nor dsi_autoFocus=\"true\" present"
        }
        assertTrue(violations.isEmpty(), "Autofocus lost:\n${violations.joinToString("\n")}")
    }

    
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

    

    private fun String.toBindingRef(): String =
        
        "Binding".let { suffix ->
            split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } } + suffix
        }

    private fun stripLineComments(src: String): String =
        src.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            
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
