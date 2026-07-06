package com.difft.android.websocket.api.push.exceptions

/**
 * The send target has no valid recipient keys: the group is confirmed invalid
 * (`GroupModel.status != 0`) or the account is deregistered. This is a **permanent**
 * failure — retrying will not change the result.
 *
 * Key: it **intentionally does not extend IOException** (mirrors Signal's
 * NotPushRegisteredException). The default `is IOException` retry branch at the end of
 * `PushSendJob.onShouldRetry` therefore will not catch it, so the job leaves the queue via
 * Result.failure() (or success when onPushSend's catch does not rethrow), stopping the
 * infinite retry churn of orphan receipts to invalid groups (issue #970 ②).
 *
 * Transient failures (weak network / not yet synced) still throw a plain
 * [java.io.IOException] → onShouldRetry=true → normal retry, never misclassified as this type.
 */
class NoValidRecipientKeysException(message: String) : Exception(message)
