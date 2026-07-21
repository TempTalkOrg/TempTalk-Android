package com.difft.android.base.utils

// CRLF must match first (the only 2->1 case) so it collapses as one unit, not two breaks.
// Members: CRLF, CR, VT, FF, NEL, LS, PS. `\n` is the target, so it is excluded (idempotent).
private val NEWLINE_FAMILY = Regex("\\r\\n|[\\r\\u000B\\u000C\\u0085\\u2028\\u2029]")

/**
 * Maps the 7-member Unicode newline family to a single `\n`. Idempotent, and
 * length-preserving except CRLF (2 chars -> 1).
 */
fun String.normalizeNewlines(): String = replace(NEWLINE_FAMILY, "\n")

// Display-only: maps the newline family to `\n` WITHOUT merging the CRLF pair (it already renders
// as a break), so this is strictly length-preserving and never shifts mention offsets.
private val DISPLAY_NEWLINES = Regex("\\r(?!\\n)|[\\u000B\\u000C\\u0085\\u2028\\u2029]")

/**
 * Display-only newline normalize. Maps lone CR and the soft separators (VT/FF/NEL/LS/PS) to `\n`
 * but leaves CRLF intact (it already renders as a break). Strictly 1->1, so mention offsets that
 * index the original body stay valid.
 */
fun String.normalizeNewlinesForDisplay(): String = replace(DISPLAY_NEWLINES, "\n")
