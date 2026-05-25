package com.difft.android.chat.common

/** UTF-8 byte size threshold above which a text body is converted to a text-file attachment. */
const val OVERSIZED_TEXT_THRESHOLD = 4096

/** Truncated body length (UTF-8 bytes) kept inline when a text body is converted to a file. */
const val OVERSIZED_TEXT_BODY_LENGTH = 2048

/** Hard cap on UTF-8 byte size for a text body; sends above this are rejected. */
const val MAX_TEXT_FILE_SIZE = 10 * 1024 * 1024

/** Visual max lines for message input fields (main composer and attachment caption inputs). */
const val MESSAGE_INPUT_MAX_LINES = 5
