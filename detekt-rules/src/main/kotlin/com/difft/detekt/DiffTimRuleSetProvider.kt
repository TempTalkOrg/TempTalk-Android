package com.difft.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * Custom Detekt rules for difft TempTalk Android.
 *
 * Scope: project-specific correctness rules. Most target coroutine / threading
 * anti-patterns from issue #723 (patterns proven to cause ANRs during the
 * RxJava → Coroutines migration, issues #718, #722); PrepareSelectMissingSelect
 * targets a WCDB chaincall misuse (#910). Grouped under one ruleset id for now —
 * split into a focused set (e.g. difft-wcdb) if the WCDB rule count grows.
 *
 * Adding new rules: append the class reference below; the SPI registration
 * in `resources/META-INF/services/dev.detekt.api.RuleSetProvider` still
 * points to this provider.
 */
class DiffTimRuleSetProvider : RuleSetProvider {

    override val ruleSetId: RuleSetId = RuleSetId(RULE_SET_ID)

    // Positional args invoke the companion `RuleSet(id, List<(Config) -> Rule>)`
    // overload which converts the list to the Map<RuleName, ...> the primary
    // constructor expects.
    override fun instance(): RuleSet = RuleSet(
        RuleSetId(RULE_SET_ID),
        listOf(
            ::BanRunBlockingOutsideTests,
            ::BlockingWcdbInSuspend,
            ::BlockingSharedPrefsInSuspend,
            ::LifecycleScopeBlockingCall,
            ::PrepareSelectMissingSelect,
            ::UnsafeWcdbConflictInsert,
        ),
    )

    private companion object {
        const val RULE_SET_ID = "difft-coroutines"
    }
}
