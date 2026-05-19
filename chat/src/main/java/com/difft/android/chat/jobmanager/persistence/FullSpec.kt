package com.difft.android.chat.jobmanager.persistence

data class FullSpec(
    val jobSpec: JobSpec,
    val constraintSpecs: List<ConstraintSpec>
) {
    val isMemoryOnly: Boolean get() = jobSpec.isMemoryOnly
}
