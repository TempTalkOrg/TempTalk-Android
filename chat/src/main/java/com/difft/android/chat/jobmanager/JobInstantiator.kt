package com.difft.android.chat.jobmanager

@JvmSuppressWildcards
class JobInstantiator(jobFactories: Map<String, Job.Factory<*>>) {

    private val jobFactories: Map<String, Job.Factory<*>> = HashMap(jobFactories)

    fun instantiate(jobFactoryKey: String, parameters: Job.Parameters, data: Data): Job {
        val factory = requireNotNull(jobFactories[jobFactoryKey]) {
            "Tried to instantiate a job with key '$jobFactoryKey', but no matching factory was found."
        }
        val job = factory.create(parameters, data)

        check(job.id == parameters.id) {
            "Parameters not supplied to job during creation"
        }

        return job
    }
}
