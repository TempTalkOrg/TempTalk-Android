package com.difft.android.migration.v2.container

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Opts a collection field out of [AbsentDefaults]' null-to-empty rule, for the rare field where "the
 * sender said nothing" and "the sender said none" are different answers.
 *
 * Must be applied as `@field:AbsentStaysNull`: on a constructor `val` Kotlin attaches an unqualified
 * annotation to the value parameter, where reflection cannot see it and the opt-out is silently
 * inert. Restricting [Target] to [AnnotationTarget.FIELD] does not redirect it.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
internal annotation class AbsentStaysNull

/**
 * Restores the empty collections that Gson drops for absent JSON fields.
 *
 * Gson builds objects through `Unsafe.allocateInstance`, so a field the JSON omits keeps `null` even
 * when its declared type is non-null with an `emptyList()` constructor default, and Kotlin emits no
 * null check to catch it. The container format lets any sender omit a field it has no data for, so
 * the importer must read that as "none". The sweep is reflective because the record types nest and
 * keep growing. Only collections are substituted — a null object field stays null, since inventing
 * one would turn "no quote" into a phantom empty quote.
 */
internal object AbsentDefaults {

    /** Fill in absent collections throughout [record]'s object graph, in place. */
    fun restore(record: Any?) = visit(record, 0)

    private fun visit(value: Any?, depth: Int) {
        if (value == null || depth > MAX_DEPTH) return
        when {
            value is Collection<*> -> value.forEach { visit(it, depth + 1) }
            value is Map<*, *> -> value.values.forEach { visit(it, depth + 1) }
            isRecord(value.javaClass) -> fieldsOf(value.javaClass).forEach { field ->
                val current = field.get(value)
                if (current != null) visit(current, depth + 1) else emptyFor(field)?.let { field.set(value, it) }
            }
        }
    }

    private fun emptyFor(field: Field): Any? = when {
        field.isAnnotationPresent(AbsentStaysNull::class.java) -> null
        List::class.java.isAssignableFrom(field.type) -> emptyList<Any>()
        Set::class.java.isAssignableFrom(field.type) -> emptySet<Any>()
        Map::class.java.isAssignableFrom(field.type) -> emptyMap<Any, Any>()
        else -> null
    }

    /**
     * Whether to walk into [type]'s fields. Platform types are leaves — recursing into a String or a
     * boxed Long would only waste time — and the test is by package name so it survives R8, which
     * never renames `java.*` / `kotlin.*`.
     */
    private fun isRecord(type: Class<*>): Boolean =
        !type.isPrimitive && !type.isEnum && !type.isArray &&
            PLATFORM_PACKAGES.none { type.name.startsWith(it) }

    private fun fieldsOf(type: Class<*>): List<Field> = FIELD_CACHE.getOrPut(type) {
        type.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .onEach { it.isAccessible = true }
    }

    private val FIELD_CACHE = ConcurrentHashMap<Class<*>, List<Field>>()

    private val PLATFORM_PACKAGES = listOf("java.", "javax.", "kotlin.", "android.")

    /** Guards against a hand-crafted container nesting forwards deeply enough to blow the stack. */
    private const val MAX_DEPTH = 32
}
