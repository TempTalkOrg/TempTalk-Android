package com.difft.android.architecture

import com.difft.android.test.guards.HostedSourceGuards
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue









class IndexActivityFragmentContainerDetailThemeGuardTest {

    private val meFragmentSource: String by lazy { resolveSourceFile("MeFragment.kt").readText() }
    private val indexDetailPagesSource: String by lazy { resolveSourceFile("IndexDetailPages.kt").readText() }

    @Test
    fun `MeFragment navigateToDetailOrActivity enumeration matches known 9 targets`() {
        val targets = extractNavigateToDetailOrActivityTargets(meFragmentSource)
        assertEquals(
            setOf(
                "ContactProfileSettingFragment", "AccountFragment", "LinkedDevicesFragment",
                "PrivacySettingFragment", "ChatSettingsFragment", "NotificationSettingsFragment",
                "AboutFragment", "LanguageFragment", "ThemeFragment",
            ),
            targets.toSet(),
            "MeFragment.kt's navigateToDetailOrActivity call sites changed -- re-verify family-D " +
                "membership for any newly added target",
        )
    }

    @Test
    fun `IndexDetailPages direct detail-pane mount enumeration matches known 7 targets`() {
        val targets = extractDirectDetailPaneTargets(indexDetailPagesSource)
        assertEquals(
            setOf("ChatFragment", "GroupChatFragment", "ContactDetailFragment", "GroupCardFragment", "GlobalSearchFragment", "FoldedGroupsFragment", "CreateGroupFragment"),
            targets.toSet(),
            "IndexDetailPages.kt's newInstance call sites changed -- " +
                "re-verify family-D membership for any newly added direct detail-pane target",
        )
    }

    @Test
    fun `no fragment reachable via fragment_container_detail combines useFlatBackground=true without applyWindowBackground awareness`() {
        val candidateClassNames = extractNavigateToDetailOrActivityTargets(meFragmentSource) +
            extractDirectDetailPaneTargets(indexDetailPagesSource) 

        val allViolations = candidateClassNames.flatMap { className ->
            
            
            val file = findSourceFileByClassName(className)
                ?: error("$className.kt not found under any known module src root -- add its module to knownSourceRoots")
            scanForUnguardedFlatBackground(file.readText()).map { "$className (${file.path}): ${it.matchedText}" }
        }

        assertTrue(
            allViolations.isEmpty(),
            "Family-D structural guard violated -- the following fragments reachable through " +
                "IndexActivity.fragment_container_detail combine useFlatBackground=true with no " +
                "applyWindowBackground awareness:\n${allViolations.joinToString("\n")}",
        )
    }

    






    @Test
    fun `destination pages write the window background only when they own the window`() {
        val files = HostedSourceGuards.derivedHostedFiles(projectRoot())
        val violations = files.flatMap { file ->
            HostedSourceGuards.windowBackgroundViolations(file.readText()).map { "${file.name} $it" }
        }
        assertTrue(
            violations.isEmpty(),
            "pass applyWindowBackground = false or ownsWindow():\n${violations.joinToString("\n")}",
        )
    }

    private val knownSourceRoots = listOf(
        "app/src/main/java",
        "chat/src/main/java",
        "base/src/main/java",
        "login/src/main/java",
        "call/src/main/java",
    )

    private fun findSourceFile(fileName: String): File? =
        knownSourceRoots
            .flatMap { root -> File(projectRoot(), root).walkTopDown().filter { it.name == fileName } }
            .firstOrNull()

    private fun resolveSourceFile(simpleFileName: String): File =
        findSourceFile(simpleFileName) ?: error("$simpleFileName not found under any known module src root")

    private fun findSourceFileByClassName(className: String): File? = findSourceFile("$className.kt")

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: break
        }
        return dir
    }
}

fun extractNavigateToDetailOrActivityTargets(source: String): List<String> =
    Regex("""navigateToDetailOrActivity\(\s*(\w+)\.newInstance""")
        .findAll(source).map { it.groupValues[1] }.distinct().toList()

fun extractDirectDetailPaneTargets(source: String): List<String> =
    Regex("""(\w+Fragment)\.newInstance\(""")
        .findAll(source).map { it.groupValues[1] }.distinct().toList()
        .ifEmpty {
            
            
            
            error(
                "extractDirectDetailPaneTargets found zero direct detail-pane mount targets in " +
                    "IndexDetailPages.kt -- the regex no longer matches the shape of its " +
                    "`XxxFragment.newInstance(` calls. A silently-empty result would make " +
                    "the family-D guard (M32) vacuously pass with zero fragments checked for this " +
                    "extraction path -- fix the regex, don't relax this check."
            )
        }
