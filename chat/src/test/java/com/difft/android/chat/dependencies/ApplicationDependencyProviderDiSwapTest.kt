package com.difft.android.chat.dependencies

import com.difft.android.chat.jobs.FastJobStorage
import com.difft.android.chat.jobs.WcdbJobStorage
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue






class ApplicationDependencyProviderDiSwapTest {

    @Test
    fun fastJobStorage_constructor_takes_WcdbJobStorage_not_JobDatabase() {
        
        
        
        val constructors = FastJobStorage::class.java.declaredConstructors
        assertTrue(
            constructors.isNotEmpty(),
            "FastJobStorage must declare at least one constructor"
        )
        val hasWcdbJobStorageParam = constructors.any { ctor ->
            ctor.parameterTypes.any { it == WcdbJobStorage::class.java }
        }
        assertTrue(
            hasWcdbJobStorageParam,
            "FastJobStorage constructor must accept WcdbJobStorage. " +
                "Found parameters: ${constructors.map { it.parameterTypes.toList() }}"
        )

        val hasLegacyJobDatabaseParam = constructors.any { ctor ->
            ctor.parameterTypes.any { it.simpleName.contains("JobDatabase", ignoreCase = true) }
        }
        assertFalse(
            hasLegacyJobDatabaseParam,
            "FastJobStorage constructor must not accept a legacy JobDatabase — " +
                "regression of hard constraint C (constructor swap)"
        )
    }

    @Test
    fun fastJobStorage_field_is_named_jobStorage_not_jobDatabase() {
        
        val fieldNames = FastJobStorage::class.java.declaredFields.map { it.name }
        assertTrue(
            fieldNames.any { it == "jobStorage" },
            "FastJobStorage must have a `jobStorage` field after rename. " +
                "Found: $fieldNames"
        )
        assertFalse(
            fieldNames.any { it == "jobDatabase" },
            "FastJobStorage must not have a `jobDatabase` field after rename. " +
                "Found: $fieldNames"
        )
    }

    @Test
    fun applicationDependencyProvider_declares_DepsEntryPoint_nested_interface() {
        
        val nestedClasses = ApplicationDependencyProvider::class.java.declaredClasses
        val depsEntryPoint = requireNotNull(
            nestedClasses.firstOrNull { it.simpleName == "DepsEntryPoint" }
        ) {
            "ApplicationDependencyProvider must declare a nested `DepsEntryPoint` interface. " +
                "Found nested classes: ${nestedClasses.map { it.simpleName }}"
        }
        assertTrue(
            depsEntryPoint.isInterface,
            "DepsEntryPoint must be declared as an interface"
        )
    }

    @Test
    fun depsEntryPoint_declares_wcdbJobStorage_accessor_method() {
        
        val depsEntryPoint = requireNotNull(
            ApplicationDependencyProvider::class.java.declaredClasses
                .firstOrNull { it.simpleName == "DepsEntryPoint" }
        ) { "DepsEntryPoint not found" }

        val methods = depsEntryPoint.declaredMethods.map { it.name }
        assertTrue(
            methods.contains("wcdbJobStorage"),
            "DepsEntryPoint must declare `wcdbJobStorage()` accessor. " +
                "Found methods: $methods"
        )

        val accessor = depsEntryPoint.declaredMethods.first { it.name == "wcdbJobStorage" }
        assertTrue(
            accessor.returnType == WcdbJobStorage::class.java,
            "DepsEntryPoint.wcdbJobStorage() must return WcdbJobStorage; " +
                "got: ${accessor.returnType}"
        )
    }
}
