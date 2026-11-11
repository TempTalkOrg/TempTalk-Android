package com.difft.android.chat.jobmanager.impl

import com.difft.android.chat.jobmanager.JobPredicate
import com.difft.android.chat.jobmanager.persistence.JobSpec




class FactoryJobPredicate(vararg factories: String) : JobPredicate {

    private val factories: Set<String> = factories.toSet()

    override fun shouldRun(jobSpec: JobSpec): Boolean = jobSpec.factoryKey in factories
}
