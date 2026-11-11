package com.difft.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider














class DiffTimRuleSetProvider : RuleSetProvider {

    override val ruleSetId: RuleSetId = RuleSetId(RULE_SET_ID)

    
    
    
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
