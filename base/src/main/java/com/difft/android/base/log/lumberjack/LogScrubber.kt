package com.difft.android.base.log.lumberjack










object LogScrubber {

    
    private val MESSAGE_ID = Regex("""(?<!\d)(\d{13})\d{8,}(\d{3})(?!\d)""")

    
    private val HEX32 = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{24}([0-9a-fA-F]{8})(?![0-9a-fA-F])""")

    
    private val URL_QUERY = Regex("""(https?://[^\s"'?]+)\?[^\s"')\]}>]+""")

    
    private val PRIVATE_IPV4 = Regex(
        """(?<![\d.])(?:10\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3})\.(\d{1,3})(?![\d.])"""
    )

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@([A-Za-z][A-Za-z0-9.-]*\.[A-Za-z]{2,})""")

    




    private val NAME_FIELD = Regex(
        """\b(callName|displayName|nickname|nickName|groupName|remark|remarkName|userName|fullName)\b(["']?\s*[=:]\s*["']?)(?!null\b)(.+?)(?=\s+\w+["']?\s*[=:]|[,;)\]}"']|$)"""
    )

    





    private val ATTACHMENT_FILE = Regex("""(/attachment/[0-9a-fA-F-]{36}/)[^\n"']*""")

    private val SECRET_KV = Regex(
        """(?i)\b(authorization|password|passwd|secret|access[_-]?key|api[_-]?key|session[_-]?key|auth[_-]?token)\b(["']?\s*[=:]\s*["']?)(?:(?:bearer|basic)\s+)?[^\s,;"'}]+"""
    )

    private val BEARER = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}""")

    private const val REDACTED = "[REDACTED]"

    




    fun scrub(message: String): String {
        if (message.isEmpty()) return message
        var s = message
        if (s.contains("://") && s.contains('?')) {
            s = URL_QUERY.replace(s) { "${it.groupValues[1]}?$REDACTED" }
        }
        if (s.containsAny(SECRET_PROBES)) {
            s = SECRET_KV.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
            s = BEARER.replace(s, "Bearer $REDACTED")
        }
        s = MESSAGE_ID.replace(s) { "${it.groupValues[1]}…${it.groupValues[2]}" }
        s = HEX32.replace(s) { "…${it.groupValues[1]}" }
        if (s.containsAny(PRIVATE_IP_PROBES)) {
            s = PRIVATE_IPV4.replace(s) { "x.x.x.${it.groupValues[1]}" }
        }
        if (s.contains('@')) {
            s = EMAIL.replace(s) { "<email>@${it.groupValues[1]}" }
        }
        if (s.containsAny(NAME_PROBES)) {
            s = NAME_FIELD.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}<redacted len=${it.groupValues[3].length}>" }
        }
        if (s.contains("/attachment/")) {
            s = ATTACHMENT_FILE.replace(s) { "${it.groupValues[1]}<file>" }
        }
        return s
    }

    private val SECRET_PROBES = listOf(
        "uthoriz", "UTHORIZ", "assw", "ASSW", "ecret", "ECRET", "_key", "-key", "Key", "KEY", "token", "Token", "TOKEN", "earer", "EARER"
    )
    private val PRIVATE_IP_PROBES = listOf("10.", "192.168.", "172.")
    private val NAME_PROBES = listOf("Name", "name", "nick", "remark")

    private fun String.containsAny(probes: List<String>): Boolean = probes.any { contains(it) }
}
