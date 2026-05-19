package com.difft.android.chat.jobmanager

@JvmSuppressWildcards
class ConstraintInstantiator(constraintFactories: Map<String, Constraint.Factory<*>>) {

    private val constraintFactories: Map<String, Constraint.Factory<*>> = HashMap(constraintFactories)

    fun instantiate(constraintFactoryKey: String): Constraint {
        val factory = checkNotNull(constraintFactories[constraintFactoryKey]) {
            "Tried to instantiate a constraint with key '$constraintFactoryKey', but no matching factory was found."
        }
        return factory.create()
    }
}
