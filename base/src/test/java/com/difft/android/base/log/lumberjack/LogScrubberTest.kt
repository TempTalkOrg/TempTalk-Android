package com.difft.android.base.log.lumberjack

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the file-sink redaction contract of [LogScrubber]. Inputs mirror the line shapes found in
 * the 2026-10 production log audit; values are synthetic.
 */
class LogScrubberTest {

    @Test
    fun messageId_keepsTimestampAndTail_hidesEmbeddedUid() {
        val line = "[DownloadAttachmentJob] messageId: 1791456565548872088899241 done"
        assertEquals("[DownloadAttachmentJob] messageId: 1791456565548…241 done", LogScrubber.scrub(line))
    }

    @Test
    fun plainTimestampAndRequestIdStayIntact() {
        val line = "[Message] sendAck success, requestId:1276734928 timestamp:1790958227449"
        assertEquals(line, LogScrubber.scrub(line))
    }

    @Test
    fun groupId_keepsLastEightHex() {
        val line = "[Message][RoomChangeTracker] trackRoom:0d27e75e5ec040748ae84dd2ca0ac4e2 type:MESSAGE"
        assertEquals("[Message][RoomChangeTracker] trackRoom:…ca0ac4e2 type:MESSAGE", LogScrubber.scrub(line))
    }

    @Test
    fun groupIdUsedAsTagIsAlsoRedacted() {
        val line = "[0d27e75e5ec040748ae84dd2ca0ac4e2] Read recipient already sent"
        assertEquals("[…ca0ac4e2] Read recipient already sent", LogScrubber.scrub(line))
    }

    @Test
    fun uuidAndShortHexAreNotTouched() {
        val line = "roomId=010a0304-9ec5-8866-8f1e-c8000b69ebc9 chat:133870177 scid:ca12"
        assertEquals(line, LogScrubber.scrub(line))
    }

    @Test
    fun attachmentUrl_stripsQueryAndMasksObjectKey() {
        val line = "url: https://fileshare-chative.s3-accelerate.amazonaws.com/e48ef893402b4d3e8bf3d0a026dadc4e?X-Amz-Signature=abc&X-Amz-Credential=def"
        assertEquals(
            "url: https://fileshare-chative.s3-accelerate.amazonaws.com/…26dadc4e?[REDACTED]",
            LogScrubber.scrub(line)
        )
    }

    @Test
    fun urlWithoutQueryIsUnchanged() {
        val line = "get global configs success: https://example.cloudfront.net/global-config.json"
        assertEquals(line, LogScrubber.scrub(line))
    }

    @Test
    fun privateIpv4_keepsLastOctet_publicIpUntouched() {
        val line = "failed to connect to chat.example.im/75.2.124.136 (port 443) from /10.52.104.21 (port 38580); candidate 192.168.1.8 52779"
        assertEquals(
            "failed to connect to chat.example.im/75.2.124.136 (port 443) from /x.x.x.21 (port 38580); candidate x.x.x.8 52779",
            LogScrubber.scrub(line)
        )
    }

    @Test
    fun email_keepsDomainOnly() {
        assertEquals("login <email>@example.com ok", LogScrubber.scrub("login alice.b@example.com ok"))
    }

    @Test
    fun nameFields_redactedWithLength_nullKept() {
        val line = "CallData(roomId=abc, callName=Alisa, remark=null, displayName: Bob Smith)"
        assertEquals(
            "CallData(roomId=abc, callName=<redacted len=5>, remark=null, displayName: <redacted len=9>)",
            LogScrubber.scrub(line)
        )
    }

    @Test
    fun nameFields_multiWordInKeyValueLineAndJson() {
        assertEquals(
            "[Call] callName=<redacted len=11> type=1on1 roomId=x",
            LogScrubber.scrub("[Call] callName=Alisa Smith type=1on1 roomId=x")
        )
        assertEquals(
            """{"displayName":"<redacted len=4>","uid":"+1"}""",
            LogScrubber.scrub("""{"displayName":"John","uid":"+1"}""")
        )
    }

    @Test
    fun attachmentFileNames_anyShapeIsReplacedWhole() {
        val dir = "/data/user/0/org.difft.temptalk/files/attachment/90145968-bf35-4a73-bb03-d910e438638f/"
        for (name in listOf(
            "file (1).ext", "Q3 Board Minutes (final).pdf", "Meeting notes, draft (1).m4a", "report v1.2 final.pdf",
            "scan.jpg|Alice-medical.jpg", "notes: Alice.pdf", "IMG_6219.MOV.encrypt", "clip.3gp", "Board Minutes final"
        )) {
            assertEquals("x: $dir<file>", LogScrubber.scrub("x: $dir$name does not exist"), name)
        }
        // A quoted path ends at the closing quote; the rest of the line is kept.
        assertEquals("""{"path":"$dir<file>","other":"x.png"}""", LogScrubber.scrub("""{"path":"${dir}a b.jpg","other":"x.png"}"""))
        // Paths outside an attachment directory are not touched by this rule.
        assertEquals("open /storage/emulated/0/DCIM/x.jpg", LogScrubber.scrub("open /storage/emulated/0/DCIM/x.jpg"))
    }

    @Test
    fun basicAuthAndJsonPasswordAreRedacted_assetNameAndVersionStringUntouched() {
        assertEquals("Authorization: [REDACTED] end", LogScrubber.scrub("Authorization: Basic dXNlcjpwYXNz end"))
        assertEquals("""{"password":"[REDACTED]"}""", LogScrubber.scrub("""{"password":"hunter2"}"""))
        assertEquals("icon name@2x.png v1.10.2.3.4", LogScrubber.scrub("icon name@2x.png v1.10.2.3.4"))
    }

    @Test
    fun attachmentFileName_replacedToEndOfLineKeepsLocalId() {
        val line = "java.lang.IllegalArgumentException: /storage/emulated/0/Android/data/org.difft.temptalk/files/attachment/90145968-bf35-4a73-bb03-d910e438638f/IMG_6219.MOV does not exist"
        assertEquals(
            "java.lang.IllegalArgumentException: /storage/emulated/0/Android/data/org.difft.temptalk/files/attachment/90145968-bf35-4a73-bb03-d910e438638f/<file>",
            LogScrubber.scrub(line)
        )
    }

    @Test
    fun secretsAndBearerTokens_redacted() {
        val line = "Authorization: Bearer eyJhbGciOi.payload.sig password=hunter2 apiKey=AKIA1234"
        assertEquals(
            "Authorization: [REDACTED] password=[REDACTED] apiKey=[REDACTED]",
            LogScrubber.scrub(line)
        )
    }

    @Test
    fun maskedUidFromReplaceUidSurvives() {
        val line = "process message -> device:2  stamp:+************241"
        assertEquals(line, LogScrubber.scrub(line))
    }

    @Test
    fun multiLineStackTraceIsScrubbedPerOccurrence() {
        val line = "onFailure()\njava.io.IOException: user x@y.io\n\tat okio.RealBufferedSource.require(RealBufferedSource.kt:225)"
        assertEquals(
            "onFailure()\njava.io.IOException: user <email>@y.io\n\tat okio.RealBufferedSource.require(RealBufferedSource.kt:225)",
            LogScrubber.scrub(line)
        )
    }
}
