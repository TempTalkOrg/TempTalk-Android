package com.difft.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * Custom Detekt rules for difft TempTalk Android.
 *
 * Scope: Coroutines / threading anti-patterns from issue #723. Each rule
 * targets a pattern proven to cause ANRs during the RxJava → Coroutines
 * migration (issues #718, #722).
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
        ),
    )

    private companion object {
        const val RULE_SET_ID = "difft-coroutines"
    }
}
