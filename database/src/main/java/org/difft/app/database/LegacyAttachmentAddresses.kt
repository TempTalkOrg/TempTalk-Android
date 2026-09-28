package org.difft.app.database

/**
 * Whether any attachment file may still sit at a pre-per-copy address (`attachment/<messageId>/`).
 *
 * Deletion is the only thing that asks: while the window is open a message's deletion must clear the
 * legacy directory as well as each row's own one, or a file whose row has just gone would be left on
 * disk with nothing left to drive its removal. Once the migration has relocated everything the legacy
 * branch is pure waste, so the migration closes the window and the branch stops running.
 *
 * Open by default: a device that has not reported otherwise is assumed to still hold legacy
 * addresses. The cost of a needless open window is one `delete` on a directory that does not exist;
 * the cost of a wrongly closed one is an orphaned file, so the default errs towards deleting more.
 */
object LegacyAttachmentAddresses {

    @Volatile
    var isWindowOpen: Boolean = true
}
