package com.difft.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File







class ManifestOrientationLockInvariantTest {

    private val manifests = listOf("app", "chat", "login", "call")
        .map { File("../$it/src/main/AndroidManifest.xml") }

    
    private val exempt = setOf(
        ".call.CriticalAlertActivity",
        "com.yalantis.ucrop.UCropActivity",
        "com.yalantis.ucrop.UCropMultipleActivity",
        "com.difft.android.migration.v2.transfer.handoff.MigrationHandoffActivity",
        ".ui.ChatForwardMessageActivity",
        ".ui.ChatPopupActivity",
        ".group.GroupChatPopupActivity",
        ".ui.textpreview.TextPreviewActivity",
        ".contacts.contactsdetail.ContactDetailActivity",
        ".contacts.groupcard.GroupCardActivity",
        ".util.NotificationTrampolineActivity",
    )

    @Test
    fun `every opaque activity declares locked, every exempt one does not`() {
        val activityRegex = Regex("""<activity\b(.*?)(?:/>|>)""", RegexOption.DOT_MATCHES_ALL)
        val nameRegex = Regex("""android:name="([^"]+)"""")
        val orientationRegex = Regex("""android:screenOrientation="([^"]+)"""")
        val missing = mutableListOf<String>()
        val wronglyLocked = mutableListOf<String>()
        manifests.forEach { file ->
            check(file.exists()) { "manifest not found relative to the :app module: ${file.path}" }
            activityRegex.findAll(file.readText()).forEach { match ->
                val block = match.groupValues[1]
                val name = nameRegex.find(block)?.groupValues?.get(1) ?: return@forEach
                val orientation = orientationRegex.find(block)?.groupValues?.get(1)
                if (name in exempt) {
                    if (orientation != null) wronglyLocked += "$name ($orientation)"
                } else if (orientation != "locked") {
                    missing += "$name (${orientation ?: "none"})"
                }
            }
        }
        assertEquals(
            "Opaque policy-managed activities without screenOrientation=\"locked\" — a cold start on a " +
                "phone held landscape will open them in landscape and snap to portrait",
            emptyList<String>(),
            missing
        )
        assertEquals(
            "Translucent activities must not carry a fixed manifest orientation (throws on API 26)",
            emptyList<String>(),
            wronglyLocked
        )
    }
}
