package com.difft.android.chat.ui

import com.difft.android.test.guards.HostedSourceGuards
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Source-shape guard for conversation pages that IndexActivity hosts in its per-tab detail stacks
 * (design: foldable detail stacks, §3.4 / §4 "Guard", "Current chat", "Unread mark"):
 * - a hosted page never finishes the Activity (that closes the home screen) or casts it to
 *   ChatActivity; it goes through `detailNavigation()` / its own arguments;
 * - every Activity-dispatcher back callback is registered with `detailBackOwner()`, so covered
 *   and hidden pages never take Back;
 * - the current-chat set is written only from onResume (onPause removes), so a page restored
 *   covered or in a hidden tab is not "current";
 * - every owner of a ChatMessageViewModel calls `onShown()` from onResume, which opens the room;
 * - every ComposeDialogManager message or bottom dialog is closed with the page's view
 *   (`dismissWithView`), since it is mounted on the home screen, not the page;
 * - (foldable phase 2, design §3.13) the hand list covers every page a `DetailDestination` builds
 *   and its same-package content files; hosted pages use no Activity-owned API (intent, results,
 *   system-bar padding, Activity pagers), never start a destination's shell directly, push with
 *   their own page as `from`; Compose destination pages consume the container's insets; the
 *   wait-showing utilities release only their own claim and hosted callers pass their wait.
 * Rules are pure functions in [HostedSourceGuards], proven on fixtures by HostedSourceGuardsTest.
 */
class HostedConversationSourceGuardTest {

    private val hostedFiles = listOf(
        "chat/src/main/java/com/difft/android/chat/ui/ChatFragment.kt",
        "chat/src/main/java/com/difft/android/chat/ui/ChatHeaderFragment.kt",
        "chat/src/main/java/com/difft/android/chat/ui/ChatMessageInputFragment.kt",
        "chat/src/main/java/com/difft/android/chat/ui/ChatMessageListFragment.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupChatFragment.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupChatHeaderFragment.kt",
        "chat/src/main/java/com/difft/android/chat/contacts/contactsdetail/ContactRemarkEditBinder.kt",
        "chat/src/main/java/com/difft/android/chat/contacts/contactsdetail/ContactDetailFragment.kt",
        "chat/src/main/java/com/difft/android/chat/contacts/contactsdetail/ContactDetailScreen.kt",
        "chat/src/main/java/com/difft/android/chat/contacts/contactsdetail/ContactDetailBottomSheetDialogFragment.kt",
        "chat/src/main/java/com/difft/android/chat/ui/SingleChatSettingFragment.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupInfoFragment.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupInfoHeaderBinder.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupInfoSettingsRows.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupInfoCryptoFlows.kt",
        "chat/src/main/java/com/difft/android/chat/ui/SingleChatSettingDialogs.kt",
        "chat/src/main/java/com/difft/android/chat/ui/SingleChatSettingRows.kt",
        "chat/src/main/java/com/difft/android/chat/ui/MessageDetailFragment.kt",
        "chat/src/main/java/com/difft/android/chat/ui/GroupInCommonFragment.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupMembersSearchFragment.kt",
        "chat/src/main/java/com/difft/android/chat/search/SearchMessageFragment.kt",
        "chat/src/main/java/com/difft/android/chat/ui/EmojiReactionFragment.kt",
        "chat/src/main/java/com/difft/android/chat/ui/EmojiReactionTabFragment.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupNotificationSettingsFragment.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupGlobalNotificationSettingsFragment.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupNotificationSettingsContent.kt",
        "chat/src/main/java/com/difft/android/chat/setting/ChatArchiveSettingsFragment.kt",
        "chat/src/main/java/com/difft/android/chat/setting/ChatArchiveSettingsContent.kt",
        "chat/src/main/java/com/difft/android/chat/setting/SaveToPhotosSettingsFragment.kt",
        "chat/src/main/java/com/difft/android/chat/setting/SaveToPhotosSettingsContent.kt",
        "chat/src/main/java/com/difft/android/chat/setting/translate/TranslateSettingsFragment.kt",
        "chat/src/main/java/com/difft/android/chat/setting/translate/TranslateSettingsContent.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupManagementFragment.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupSelectionFragments.kt",
        "app/src/main/java/com/difft/android/setting/AccountFragment.kt",
        "app/src/main/java/com/difft/android/setting/PrivacySettingFragment.kt",
        "app/src/main/java/com/difft/android/setting/ChatSettingsFragment.kt",
        "app/src/main/java/com/difft/android/setting/NotificationSettingsFragment.kt",
        "app/src/main/java/com/difft/android/setting/NotificationSettingsContent.kt",
        "app/src/main/java/com/difft/android/setting/NotificationSettingsRows.kt",
        "app/src/main/java/com/difft/android/setting/CallNotificationSettingsFragment.kt",
        "app/src/main/java/com/difft/android/setting/CallNotificationSettingsContent.kt",
        "app/src/main/java/com/difft/android/setting/CriticalAlertSettingsFragment.kt",
        "app/src/main/java/com/difft/android/setting/CriticalAlertSettingsContent.kt",
        "app/src/main/java/com/difft/android/setting/AboutFragment.kt",
        "app/src/main/java/com/difft/android/setting/AboutPageView.kt",
        "app/src/main/java/com/difft/android/setting/theme/ThemeFragment.kt",
        "app/src/main/java/com/difft/android/setting/language/LanguageFragment.kt",
        "app/src/main/java/com/difft/android/linkeddevices/LinkedDevicesFragment.kt",
        "app/src/main/java/com/difft/android/linkeddevices/LinkedDevicesScreen.kt",
        "login/src/main/java/com/difft/android/login/ContactProfileSettingFragment.kt",
        "app/src/main/java/com/difft/android/setting/ScreenLockSettingFragment.kt",
        "app/src/main/java/com/difft/android/setting/SetPasscodeTimeoutFragment.kt",
        "app/src/main/java/com/difft/android/setting/SetCustomIdFragment.kt",
        "app/src/main/java/com/difft/android/setting/DeleteAccountFragment.kt",
        "app/src/main/java/com/difft/android/setting/ProxySettingsFragment.kt",
        "app/src/main/java/com/difft/android/setting/VoicePlaybackSpeedSettingsFragment.kt",
        "app/src/main/java/com/difft/android/setting/VoiceChangerSettingsFragment.kt",
        "app/src/main/java/com/difft/android/setting/NotificationContentDisplaySettingsFragment.kt",
        "app/src/main/java/com/difft/android/setting/BackgroundConnectionSettingsFragment.kt",
        "app/src/main/java/com/difft/android/setting/BackgroundConnectionPage.kt",
        "app/src/main/java/com/difft/android/setting/BackgroundConnectionRows.kt",
        "app/src/main/java/com/difft/android/setting/notificationtest/NotificationTestFragment.kt",
        "app/src/main/java/com/difft/android/setting/notificationtest/NotificationTestContent.kt",
        "app/src/main/java/com/difft/android/setting/notificationtest/NotificationTestDialogs.kt",
        "app/src/main/java/com/difft/android/setting/NotificationContentDisplaySettingsContent.kt",
        "app/src/main/java/com/difft/android/setting/PasscodeTimeoutContent.kt",
        "app/src/main/java/com/difft/android/setting/ProxySettingsContent.kt",
        "app/src/main/java/com/difft/android/setting/language/LanguageSettingsContent.kt",
        "app/src/main/java/com/difft/android/setting/theme/ThemeSettingsContent.kt",
        "app/src/main/java/com/difft/android/search/GlobalSearchFragment.kt",
        "app/src/main/java/com/difft/android/search/SearchMoreFragment.kt",
    )

    /** Utilities hosted pages call that show the shared wait dialog themselves. */
    private val waitUtilities = listOf(
        "chat/src/main/java/com/difft/android/chat/ui/SelectChatsUtils.kt",
        "chat/src/main/java/com/difft/android/chat/util/SaveAttachmentUtil.kt",
    )

    private val currentChatWriters = listOf(
        "chat/src/main/java/com/difft/android/chat/ui/ChatFragment.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupChatFragment.kt",
        "chat/src/main/java/com/difft/android/chat/ui/ChatPopupActivity.kt",
        "chat/src/main/java/com/difft/android/chat/group/GroupChatPopupActivity.kt",
    )

    @Test
    fun `hosted conversation pages never finish the Activity or cast it to ChatActivity`() {
        val forbidden = listOf(
            Regex("""activity\?\.finish\(\)"""),
            Regex("""requireActivity\(\)\.finish\(\)"""),
            Regex("""\bas\??\s+ChatActivity\b"""),
        )
        val violations = hostedFiles.flatMap { path ->
            val source = read(path)
            forbidden.flatMap { regex -> regex.findAll(source).map { "$path:${lineOf(source, it.range.first)} ${it.value}" } }
        }
        assertTrue(violations.isEmpty(), "use detailNavigation().pop/closeConversation instead:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `hosted pages show wait dialogs only through their page-scoped claim`() {
        // The dialog is mounted on the home screen: a direct showWait outlives a closed page, and a
        // global or per-Activity dismissWait closes another page's claim. Use PageWait.
        val direct = Regex("""ComposeDialogManager\.(showWait|dismissWait)\(""")
        val violations = hostedFiles.flatMap { path ->
            val source = read(path)
            direct.findAll(source).map { "$path:${lineOf(source, it.range.first)} ${it.value}" }.toList()
        }
        assertTrue(violations.isEmpty(), "use PageWait:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `hosted pages bind every message and bottom dialog to their view`() {
        // The dialog is mounted on the home screen: one left up after the page closes runs its
        // callbacks against a detached page. Wrap the show call: dismissWithView(ComposeDialogManager.show…(…)).
        val show = Regex("""ComposeDialogManager\.(showMessageDialog|showMessageDialogForJava|showBottomDialog)\(""")
        val calls = hostedFiles.flatMap { path ->
            val source = read(path)
            show.findAll(source).map { Triple(path, source, it) }.toList()
        }
        assertTrue(calls.isNotEmpty(), "no ComposeDialogManager dialog found: the guard would pass vacuously")
        val violations = calls
            .filterNot { (_, source, match) -> source.substring(0, match.range.first).trimEnd().endsWith("dismissWithView(") }
            .map { (path, source, match) -> "$path:${lineOf(source, match.range.first)} ${match.value}" }
        assertTrue(violations.isEmpty(), "wrap in dismissWithView(...):\n${violations.joinToString("\n")}")
    }

    /**
     * Utilities that show the shared wait dialog themselves must take the hosted page's PageWait,
     * so a page closed mid-request does not strand the dialog on the home screen.
     */
    @Test
    fun `hosted pages pass their PageWait to wait-showing utilities`() {
        // The whole call line: the arguments themselves contain parentheses.
        val call = Regex("""AvatarPreviewLauncher\.preview\w+\([^\n]*""")
        val calls = hostedFiles.flatMap { path ->
            val source = read(path)
            call.findAll(source).map { path to it.value }.toList()
        }
        assertTrue(calls.isNotEmpty(), "no AvatarPreviewLauncher call found: the guard would pass vacuously")
        val violations = calls.filterNot { (_, text) -> Regex("""wait = \w+Wait\b""").containsMatchIn(text) }
        assertTrue(violations.isEmpty(), "pass the flow's PageWait (wait = <flow>Wait):\n${violations.joinToString("\n")}")

        val saves = hostedFiles.flatMap { path ->
            HostedSourceGuards.callsWithoutWait(read(path), listOf("saveToNotes", "saveWithUI")).map { "$path $it" }
        }
        assertTrue(saves.isEmpty(), "pass wait = <flow>Wait:\n${saves.joinToString("\n")}")
    }

    @Test
    fun `wait-showing utilities release only their own claim`() {
        val violations = waitUtilities.flatMap { path ->
            HostedSourceGuards.noArgDismissWaits(read(path)).map { "$path $it" }
        }
        assertTrue(violations.isEmpty(), "release by token or PageWait (TaskWait):\n${violations.joinToString("\n")}")
    }

    @Test
    fun `the hosted list covers every destination page and its content files`() {
        val root = projectRoot()
        val listed = hostedFiles.map { File(root, it).canonicalFile }.toSet()
        val missing = HostedSourceGuards.derivedHostedFiles(root).map { it.canonicalFile }.filterNot { it in listed }
        assertTrue(missing.isEmpty(), "add to hostedFiles (and make them pass):\n${missing.joinToString("\n") { it.relativeTo(root).path }}")
    }

    @Test
    fun `hosted pages use no Activity-owned API and never start a destination shell`() {
        val shells = HostedSourceGuards.destinationShellNames(projectRoot())
        assertTrue(shells.isNotEmpty(), "no destination shell found: the shell rule would pass vacuously")
        val violations = hostedFiles.flatMap { path ->
            val source = read(path)
            (HostedSourceGuards.forbiddenCalls(source) + HostedSourceGuards.shellStarts(source, shells)).map { "$path $it" }
        }
        assertTrue(violations.isEmpty(), "hosted pages share IndexActivity:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `hosted pages push with their own page as from`() {
        val violations = hostedFiles.flatMap { path -> HostedSourceGuards.pushesWithoutFrom(read(path)).map { "$path $it" } }
        assertTrue(violations.isEmpty(), "push(from = this, destination):\n${violations.joinToString("\n")}")
    }

    @Test
    fun `Compose destination pages consume the container's insets`() {
        val violations = HostedSourceGuards.hostedFragmentFiles(projectRoot())
            .filter { HostedSourceGuards.composeWithoutContainerInsets(it.readText()) }
        assertTrue(violations.isEmpty(), "use Modifier.detailPageInsets / consumeContainerInsets:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `the message list marks read and seen only while it is the visible top`() {
        val source = read("chat/src/main/java/com/difft/android/chat/ui/ChatMessageListFragment.kt")
        listOf("sendAndUpdateMessageRead", "checkAndRecordConfidentialPlaceholders").forEach { name ->
            val body = functionBody(source, name) ?: fail("$name not found")
            val gate = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("//") && it != "{" }
            assertTrue(gate?.contains("isVisibleTopNow()") == true, "$name must return first unless isVisibleTopNow()")
        }
        assertTrue(Regex("""onVisibleTopAgain \{\s*sendAndUpdateMessageRead\(\)\s*checkAndRecordConfidentialPlaceholders\(\)""")
            .containsMatchIn(source), "catch up read and seen when the list is the visible top again")
        // A jump opened from a hosted search page lands while that page's keyboard closes.
        assertTrue(source.contains("jumpTarget.reanchorOnLayout(delta)"), "re-apply a pending jump when the list grows")
        assertTrue(source.contains("jumpTarget.set(position)"), "a message jump records its target")
    }

    @Test
    fun `notification settings pages track their writes and re-read the outcome`() {
        listOf(
            "chat/src/main/java/com/difft/android/chat/group/GroupNotificationSettingsFragment.kt",
            "chat/src/main/java/com/difft/android/chat/group/GroupGlobalNotificationSettingsFragment.kt",
        ).forEach { path ->
            val source = read(path)
            listOf("launchOutlivingPage {", "PageOperations.track(", "waitWhileVisibleTop(PageOperations.running(", "onOperationEnded(")
                .forEach { assertTrue(source.contains(it), "$path: missing $it (design §3.11 O1)") }
        }
        val group = read("chat/src/main/java/com/difft/android/chat/group/GroupNotificationSettingsFragment.kt")
        assertTrue(functionBody(group, "onResume")?.contains("readValues()") == true, "re-read on resume (design §3.4)")
        assertTrue(group.contains("changeSelfNotification("), "store an accepted change locally, or the re-read reverts it")
    }

    @Test
    fun `hosted pages with a search field follow the detail keyboard policy`() {
        // Search pages open the keyboard only on a fresh open: the layout's autofocus would also
        // fire after a fold recreation, so it is off when hosted.
        listOf(
            "chat/src/main/java/com/difft/android/chat/search/SearchMessageFragment.kt",
            "chat/src/main/java/com/difft/android/chat/group/GroupMembersSearchFragment.kt",
            "app/src/main/java/com/difft/android/search/GlobalSearchFragment.kt",
        ).forEach { path ->
            val source = read(path)
            assertTrue(source.contains("detailKeyboard(showOnFirstOpen = true"), "$path: detailKeyboard")
            assertTrue(source.contains("if (isInDetailStack()) binding.searchInput.autoFocus = false"), "$path: layout autofocus off when hosted")
        }
        val commonGroups = read("chat/src/main/java/com/difft/android/chat/ui/GroupInCommonFragment.kt")
        assertTrue(commonGroups.contains("detailKeyboard("), "GroupInCommonFragment: hide the keyboard when covered, lease below API 30")
        // Search layer pages take the keyboard back when the one-pane conversation layer over them pops (D1).
        listOf(
            "chat/src/main/java/com/difft/android/chat/search/SearchMessageFragment.kt",
            "app/src/main/java/com/difft/android/search/GlobalSearchFragment.kt",
        ).forEach { path ->
            val source = read(path)
            assertTrue(Regex("""class \w+ : Fragment\(\)[^{]*\bUncoverableSearchPage\b""").containsMatchIn(source), "$path: implement UncoverableSearchPage")
            assertTrue(source.contains("override fun onUncovered()"), "$path: re-focus the field on uncover")
            assertTrue(source.contains("override fun onCovered()"), "$path: let the keyboard go when a conversation layer covers the page")
        }
    }

    @Test
    fun `conversation roots write the window's soft-input mode only through the lease`() {
        listOf(
            "chat/src/main/java/com/difft/android/chat/ui/ChatFragment.kt",
            "chat/src/main/java/com/difft/android/chat/group/GroupChatFragment.kt",
        ).forEach { path ->
            val source = HostedSourceGuards.stripLineComments(read(path))
            assertTrue(!source.contains("setSoftInputMode("), "$path: use SoftInputModeLease")
            assertTrue(source.contains("SoftInputModeLease.acquireResize("), "$path: acquire the lease")
            assertTrue(source.contains("SoftInputModeLease.release("), "$path: release the lease")
        }
    }

    @Test
    fun `hosted back callbacks are registered with detailBackOwner`() {
        val calls = hostedFiles.flatMap { path ->
            val source = read(path)
            Regex("""\.addCallback\(""").findAll(source).map { match ->
                val owner = HostedSourceGuards.callArguments(source, match.range.last)?.firstOrNull()
                    ?: error("$path: unreadable addCallback owner")
                path to owner.trim()
            }.toList()
        }
        assertTrue(calls.isNotEmpty(), "no addCallback found: the guard would pass vacuously")
        val violations = calls.filterNot { (_, owner) -> Regex("""(\w+\.)?detailBackOwner\(\)""").matches(owner) }
        assertTrue(violations.isEmpty(), "register with detailBackOwner():\n${violations.joinToString("\n")}")
    }

    @Test
    fun `the current chat is written only from onResume`() {
        currentChatWriters.forEach { path ->
            val source = read(path)
            val writes = Regex("""addToCurrentChat\(""").findAll(source).toList()
            assertEquals(1, writes.size, "$path: expected exactly one addToCurrentChat")
            writes.forEach { write ->
                assertEquals("onResume", enclosingFunction(source, write.range.first), "$path: addToCurrentChat outside onResume")
            }
        }
    }

    @Test
    fun `every ChatMessageViewModel owner calls onShown from onResume`() {
        val creators = listOf("chat/src/main/java", "app/src/main/java").flatMap { root ->
            File(projectRoot(), root).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.readText().contains("withCreationCallback<ChatMessageViewModelFactory>") }
                .toList()
        }
        assertEquals(
            setOf("ChatFragment.kt", "GroupChatFragment.kt", "ChatPopupActivity.kt", "GroupChatPopupActivity.kt"),
            creators.map { it.name }.toSet(),
            "ChatMessageViewModel creators changed: a new owner must call onShown() from onResume",
        )
        creators.forEach { file ->
            val body = functionBody(file.readText(), "onResume") ?: fail("${file.name}: no onResume")
            assertTrue(body.contains(".onShown()"), "${file.name}: onResume must call chatViewModel.onShown()")
        }
    }

    private fun read(path: String): String {
        val file = File(projectRoot(), path)
        check(file.isFile) { "$path not found: update the guard's file list" }
        return file.readText()
    }

    private fun lineOf(source: String, index: Int): Int = source.substring(0, index).count { it == '\n' } + 1

    private fun enclosingFunction(source: String, index: Int): String? =
        Regex("""fun\s+(\w+)\s*\(""").findAll(source.substring(0, index)).lastOrNull()?.groupValues?.get(1)

    private fun functionBody(source: String, name: String): String? {
        val start = Regex("""fun\s+$name\s*\([^)]*\)\s*\{""").find(source) ?: return null
        var depth = 0
        for (i in start.range.last until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start.range.last, i + 1)
            }
        }
        return null
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: break
        }
        return dir
    }
}
