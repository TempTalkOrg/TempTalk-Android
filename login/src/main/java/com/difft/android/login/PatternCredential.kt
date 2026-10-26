package com.difft.android.login

/**
 * The stored pattern string: `hash:salt`, with a trailing `:1` once the pattern was created under
 * the segment-aware dot selection. The marker lives inside the string so it follows the hash through
 * every store, copy and clear of the pattern; readers that split on `:` and take the first two
 * parts are unaffected.
 */
data class PatternCredential(
    val hash: String,
    val salt: String,
    val createdWithNewRule: Boolean,
) {
    fun format(): String = if (createdWithNewRule) "$hash:$salt:$NEW_RULE_MARKER" else "$hash:$salt"

    companion object {
        private const val NEW_RULE_MARKER = "1"

        /** Hashes [sequence] (dot ids in order) into a credential created under the current rule. */
        fun create(sequence: List<Int>): PatternCredential {
            val (salt, hash) = PasscodeUtil.createSaltAndHashByPassword(PatternUnlockPolicy.sequenceKey(sequence))
            return PatternCredential(hash, salt, createdWithNewRule = true)
        }

        fun parse(stored: String): PatternCredential? {
            val parts = stored.split(":")
            if (parts.size < 2) return null
            return PatternCredential(parts[0], parts[1], createdWithNewRule = parts.getOrNull(2) == NEW_RULE_MARKER)
        }
    }
}
