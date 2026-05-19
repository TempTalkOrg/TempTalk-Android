package com.difft.android.chat.dependencies

import com.difft.android.chat.jobs.FastJobStorage
import com.difft.android.chat.jobs.WcdbJobStorage
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural regression guards for the DI swap in [ApplicationDependencyProvider].
 *
 * Verifies at compile/reflection time that:
 * 1. [FastJobStorage] takes a single [WcdbJobStorage] constructor parameter — NOT
 *    a legacy `JobDatabase`. Hard constraint C symmetry check — the constructor
 *    parameter type is the ONLY thing that changes in `FastJobStorage`.
 * 2. [ApplicationDependencyProvider] declares the `DepsEntryPoint` nested interface
 *    that exposes `wcdbJobStorage()` — this is the bridge used by
 *    `provideJobManager` to get the Hilt-managed [WcdbJobStorage] singleton
 *    from a non-Hilt construction site.
 *
 * No runtime I/O, no Robolectric, no Hilt — pure reflection checks, so these
 * are NOT @Ignore-d and run on every test pass.
 */
class ApplicationDependencyProviderDiSwapTest {

    @Test
    fun fastJobStorage_constructor_takes_WcdbJobStorage_not_JobDatabase() {
        // Reflective contract check: the FastJobStorage constructor must have
        // WcdbJobStorage as its sole parameter type. Any legacy JobDatabase
        // reference would indicate the migration regressed.
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
        // Task Open Question 6 resolution: the private field that used to be
        // `jobDatabase` has been renamed to `jobStorage` to match the new type.
        // Any residual `jobDatabase` field would indicate a half-applied rename.
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
        // Hilt EntryPoint bridge (design §4.2.3 / D16): verify the nested
        // interface is declared so `EntryPointAccessors.fromApplication(..., DepsEntryPoint.class)`
        // will find it at runtime.
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
        // Verify the exact method signature: `fun wcdbJobStorage(): WcdbJobStorage`.
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
