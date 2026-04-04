package com.difft.android.chat.message

/**
 * Confidential message placeholder.
 *
 * When the recipient reads a confidential message, the sender's message converts to this type.
 * Displayed with notify-style layout (no read status). Once the sender closes the page, the message is deleted locally.
 */
class ConfidentialPlaceholderChatMessage : ChatMessage()