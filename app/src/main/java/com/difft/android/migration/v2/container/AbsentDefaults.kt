package com.difft.android.migration.v2.container

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap









@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
internal annotation class AbsentStaysNull








@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
internal annotation class AbsentIsEmpty











internal object AbsentDefaults {

    
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
        field.isAnnotationPresent(AbsentIsEmpty::class.java) && field.type == String::class.java -> ""
        List::class.java.isAssignableFrom(field.type) -> emptyList<Any>()
        Set::class.java.isAssignableFrom(field.type) -> emptySet<Any>()
        Map::class.java.isAssignableFrom(field.type) -> emptyMap<Any, Any>()
        else -> null
    }

    




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

    
    private const val MAX_DEPTH = 32
}
