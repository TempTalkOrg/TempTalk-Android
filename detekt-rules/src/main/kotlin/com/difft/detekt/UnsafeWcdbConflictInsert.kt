package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * Flags WCDB conflict-resolving inserts on a table accessor that is NOT on the
 * natural-key-primary-key allowlist — the `databaseId` auto-increment-PK footgun
 * from #914.
 *
 * ## The footgun
 * WCDB's conflict inserts resolve their `ON CONFLICT` target on the table's
 * PRIMARY KEY, not on a natural-key unique index. Most domain tables (`message`,
 * `room`, `contactor`, `attachment`, `quote`, …) use an auto-increment
 * `databaseId` as the PK. Freshly-built models default to `databaseId = 0`, so a
 * batch of N such rows all collide on PK `0`:
 *   - `insertOrReplace*` (`INSERT OR REPLACE`) deletes the prior row each time →
 *     N rows collapse into 1 (insert 100, end up with 1).
 *   - `insertOrIgnore*` (`INSERT OR IGNORE`) keeps the first and silently drops
 *     the rest → same 1-surviving-row data loss, different mechanism.
 * The natural-key unique index does not save this: it only throws on a plain
 * `insertObject` duplicate; it does not redirect the conflict target away from
 * the PK. Correct pattern for `databaseId`-PK tables: read-then-write (recover
 * the existing `databaseId`, set it, then `insertOrReplace`/update).
 *
 * ## Detection (allowlist / default-deny — #914's preferred strategy)
 * Any `insertOrReplaceObject(s)` / `insertOrIgnoreObject(s)` on a table accessor
 * NOT in [NATURAL_KEY_PK_ACCESSORS] is flagged. The allowlist holds every
 * accessor whose PRIMARY KEY is its natural key (verified against the model's
 * `@WCDBField(isPrimary)` / `multiPrimaries`), so conflict-insert is safe there.
 * Adding a table to the allowlist requires confirming its PK is the natural key.
 *
 * Both call shapes are covered: `wcdb.<table>.insertOr…` and the bare
 * `<table>.insertOr…` used inside `fun WCDB.…` extensions (e.g. WCDBExtensions),
 * which is where read-then-write boilerplate — and future footguns — live.
 *
 * ## Known gaps (deliberate — zero current usage)
 * The chaincall builder `prepareInsert().orReplace()/.orIgnore().execute()` and
 * the handle-level `db.insertOr…(obj, fields, tableName)` overload (table named
 * by a string arg) are not detected. Neither is used in the repo; `@Suppress`
 * with a note if one is ever introduced on a natural-key table.
 *
 * Precedent: [PrepareSelectMissingSelect] (#910) — sibling compile-time WCDB
 * misuse guard.
 */
class UnsafeWcdbConflictInsert(config: Config) : Rule(
    config,
    "WCDB conflict insert (insertOrReplace*/insertOrIgnore*) on a databaseId-PK " +
        "table collapses a batch into one row — silent data loss.",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression?.text ?: return
        if (callee !in CONFLICT_INSERT_METHODS) return

        val accessor = expression.tableAccessor() ?: return
        if (accessor in NATURAL_KEY_PK_ACCESSORS) return

        report(
            Finding(
                entity = Entity.from(expression),
                message = "`$callee` on `$accessor` resolves its conflict on the PRIMARY KEY. " +
                    "If `$accessor` uses an auto-increment `databaseId` PK, a batch of " +
                    "freshly-built models (all `databaseId = 0`) collapses into a single row — " +
                    "silent data loss. CORRECT pattern: read-then-write (recover the existing " +
                    "`databaseId` by natural key, then insertOrReplace/update). " +
                    "ONLY IF `$accessor`'s model declares its PRIMARY KEY as the natural key " +
                    "(`@WCDBField(isPrimary = true)` on the business field, NOT an auto-increment " +
                    "`databaseId`): add \"$accessor\" to NATURAL_KEY_PK_ACCESSORS in " +
                    "detekt-rules/src/main/kotlin/com/difft/detekt/UnsafeWcdbConflictInsert.kt. (#914)",
            )
        )
    }

    /**
     * The immediate table accessor of a conflict-insert call:
     *   - `wcdb.readInfo.insertOr…` / `this.readInfo.insertOr…` → selector `readInfo`
     *   - bare `readInfo.insertOr…` (inside a `fun WCDB.…` extension)       → `readInfo`
     * Uses [KtQualifiedExpression] so both `.` and `?.` chains are recognized.
     * Returns null when the call has no receiver.
     */
    private fun KtCallExpression.tableAccessor(): String? {
        val qualified = parent as? KtQualifiedExpression ?: return null
        return when (val receiver = qualified.receiverExpression) {
            is KtQualifiedExpression -> receiver.selectorExpression?.text
            else -> receiver.text
        }
    }

    private companion object {
        val CONFLICT_INSERT_METHODS = setOf(
            "insertOrReplaceObject",
            "insertOrReplaceObjects",
            "insertOrIgnoreObject",
            "insertOrIgnoreObjects",
        )

        /**
         * Accessors whose PRIMARY KEY is the natural key — conflict-insert is safe here.
         *
         * ## How to add a table (when CI flags a new conflict-insert)
         * 1. Open the table's model and read its key declaration.
         * 2. Add it here ONLY IF the PRIMARY KEY is the natural/business key —
         *    `@WCDBField(isPrimary = true)` on the business field, or
         *    `@WCDBTableCoding(multiPrimaries = [...])` on business columns.
         * 3. Do NOT add it if the PK is an auto-increment `databaseId`
         *    (`@WCDBField(isPrimary = true, isAutoIncrement = true)`): that is the
         *    footgun this rule exists to catch — fix the call to read-then-write instead.
         * 4. Append the accessor name with a `// <table> — PK <key>` comment.
         *
         * Adding here is a deliberate, reviewed assertion that the table is upsert-safe.
         */
        val NATURAL_KEY_PK_ACCESSORS = setOf(
            "publicKeyInfo",         // public_key_info — PK uid (isPrimary = true)
            "groupCryptoKeys",       // group_crypto_keys — PK gid (isPrimary = true)
            "draft",                 // draft — PK roomId (isPrimary = true)
            "pendingMessageNew",     // pending_message_new — PK messageId (isPrimary = true)
            "resetIdentityKey",      // reset_identity_key — PK uid (isPrimary = true)
            "readInfo",              // read_info — multiPrimaries(roomId, uid)
            "notificationCache",     // notification_cache — multiPrimaries(conversationId, timestamp)
            "pendingRemovalContact", // pending_removal_contact — PK uid (isPrimary = true, #917)
            "jobSpec",               // job_spec — PK id (isPrimary = true)
            "jobConstraint",         // job_constraint — multiPrimaries(jobSpecId, factoryKey)
            "favoriteGifs",          // favorite_gifs — PK fileHash (isPrimary = true, GIF favorites M3)
        )
    }
}
