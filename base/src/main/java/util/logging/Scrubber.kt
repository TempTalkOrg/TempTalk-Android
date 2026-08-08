package util.logging

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Scrub data for possibly sensitive information.
 */
object Scrubber {

    /**
     * The middle group will be censored.
     * Supposedly, the shortest international phone numbers in use contain seven digits.
     * Handles URL encoded +, %2B
     */
    private val E164_PATTERN: Pattern = Pattern.compile("""(\+|%2B)(\d{5,13})(\d{2})""")
    private const val E164_CENSOR = "*************"

    /**
     * The second group will be censored.
     */
    private val CRUDE_EMAIL_PATTERN: Pattern = Pattern.compile("""\b([^\s/])([^\s/]*@[^\s]+)""")
    private const val EMAIL_CENSOR = "...@..."

    /**
     * The middle group will be censored.
     */
    private val GROUP_ID_V1_PATTERN: Pattern = Pattern.compile("""(__)(textsecure_group__![^\s]+)([^\s]{2})""")
    private const val GROUP_ID_V1_CENSOR = "...group..."

    /**
     * The middle group will be censored.
     */
    private val GROUP_ID_V2_PATTERN: Pattern = Pattern.compile("""(__)(signal_group__v2__![^\s]+)([^\s]{2})""")
    private const val GROUP_ID_V2_CENSOR = "...group_v2..."

    /**
     * The middle group will be censored.
     */
    private val UUID_PATTERN: Pattern = Pattern.compile(
        "(JOB::)?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{10})([0-9a-f]{2})",
        Pattern.CASE_INSENSITIVE
    )
    private const val UUID_CENSOR = "********-****-****-****-**********"

    /**
     * The entire string is censored.
     */
    private val IPV4_PATTERN: Pattern = Pattern.compile(
        "\\b" +
            "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\." +
            "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\." +
            "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\." +
            "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)" +
            "\\b"
    )
    private const val IPV4_CENSOR = "...ipv4..."

    /**
     * The domain name except for TLD will be censored.
     */
    private val DOMAIN_PATTERN: Pattern = Pattern.compile(
        """([a-z0-9]+\.)+([a-z0-9\-]*[a-z\-][a-z0-9\-]*)""",
        Pattern.CASE_INSENSITIVE
    )
    private const val DOMAIN_CENSOR = "***."
    private val TOP_100_TLDS: Set<String> = hashSetOf(
        "com", "net", "org", "jp", "de", "uk", "fr", "br", "it", "ru", "es", "me", "gov", "pl", "ca", "au", "cn", "co", "in",
        "nl", "edu", "info", "eu", "ch", "id", "at", "kr", "cz", "mx", "be", "tv", "se", "tr", "tw", "al", "ua", "ir", "vn",
        "cl", "sk", "ly", "cc", "to", "no", "fi", "us", "pt", "dk", "ar", "hu", "tk", "gr", "il", "news", "ro", "my", "biz",
        "ie", "za", "nz", "sg", "ee", "th", "io", "xyz", "pe", "bg", "hk", "lt", "link", "ph", "club", "si", "site",
        "mobi", "by", "cat", "wiki", "la", "ga", "xxx", "cf", "hr", "ng", "jobs", "online", "kz", "ug", "gq", "ae", "is",
        "lv", "pro", "fm", "tips", "ms", "sa", "app"
    )

    @JvmStatic
    fun scrub(input: CharSequence): CharSequence {
        var result: CharSequence = input
        result = scrubE164(result)
        result = scrubEmail(result)
        result = scrubGroupsV1(result)
        result = scrubGroupsV2(result)
        result = scrubUuids(result)
        result = scrubDomains(result)
        result = scrubIpv4(result)
        return result
    }

    private fun scrubE164(input: CharSequence): CharSequence =
        scrub(input, E164_PATTERN) { matcher, output ->
            output.append(matcher.group(1))
                .append(E164_CENSOR, 0, matcher.group(2).length)
                .append(matcher.group(3))
        }

    private fun scrubEmail(input: CharSequence): CharSequence =
        scrub(input, CRUDE_EMAIL_PATTERN) { matcher, output ->
            output.append(matcher.group(1))
                .append(EMAIL_CENSOR)
        }

    private fun scrubGroupsV1(input: CharSequence): CharSequence =
        scrub(input, GROUP_ID_V1_PATTERN) { matcher, output ->
            output.append(matcher.group(1))
                .append(GROUP_ID_V1_CENSOR)
                .append(matcher.group(3))
        }

    private fun scrubGroupsV2(input: CharSequence): CharSequence =
        scrub(input, GROUP_ID_V2_PATTERN) { matcher, output ->
            output.append(matcher.group(1))
                .append(GROUP_ID_V2_CENSOR)
                .append(matcher.group(3))
        }

    private fun scrubUuids(input: CharSequence): CharSequence =
        scrub(input, UUID_PATTERN) { matcher, output ->
            if (matcher.group(1) != null && !matcher.group(1).isEmpty()) {
                output.append(matcher.group(1))
                    .append(matcher.group(2))
                    .append(matcher.group(3))
            } else {
                output.append(UUID_CENSOR)
                    .append(matcher.group(3))
            }
        }

    private fun scrubDomains(input: CharSequence): CharSequence =
        scrub(input, DOMAIN_PATTERN) { matcher, output ->
            val match = matcher.group(0)
            if (matcher.groupCount() == 2 &&
                TOP_100_TLDS.contains(matcher.group(2).lowercase(Locale.US)) &&
                !match.endsWith("whispersystems.org") &&
                !match.endsWith("signal.org")
            ) {
                output.append(DOMAIN_CENSOR)
                    .append(matcher.group(2))
            } else {
                output.append(match)
            }
        }

    private fun scrubIpv4(input: CharSequence): CharSequence =
        scrub(input, IPV4_PATTERN) { _, output ->
            output.append(IPV4_CENSOR)
        }

    private inline fun scrub(
        input: CharSequence,
        pattern: Pattern,
        processMatch: (Matcher, StringBuilder) -> Unit
    ): CharSequence {
        val output = StringBuilder(input.length)
        val matcher = pattern.matcher(input)

        var lastEndingPos = 0

        while (matcher.find()) {
            output.append(input, lastEndingPos, matcher.start())

            processMatch(matcher, output)

            lastEndingPos = matcher.end()
        }

        return if (lastEndingPos == 0) {
            // there were no matches, save copying all the data
            input
        } else {
            output.append(input, lastEndingPos, input.length)
            output
        }
    }
}
