package com.difft.android.chat.jobs

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException

/**
 * Adapts values whose runtime type may differ from their declaration type. This
 * is necessary when a field's type is not the same type that GSON should create
 * when deserializing that field. For example, consider these types:
 * ```
 * abstract class Shape {
 *     int x;
 *     int y;
 * }
 * class Circle extends Shape {
 *     int radius;
 * }
 * class Rectangle extends Shape {
 *     int width;
 *     int height;
 * }
 * class Diamond extends Shape {
 *     int width;
 *     int height;
 * }
 * class Drawing {
 *     Shape bottomShape;
 *     Shape topShape;
 * }
 * ```
 *
 * Without additional type information, the serialized JSON is ambiguous. Is
 * the bottom shape in this drawing a rectangle or a diamond?
 * ```
 * {
 *     "bottomShape": {
 *         "width": 10,
 *         "height": 5,
 *         "x": 0,
 *         "y": 0
 *     },
 *     "topShape": {
 *         "radius": 2,
 *         "x": 4,
 *         "y": 1
 *     }
 * }
 * ```
 *
 * This class addresses this problem by adding type information to the
 * serialized JSON and honoring that type information when the JSON is
 * deserialized:
 * ```
 * {
 *     "bottomShape": {
 *         "type": "Diamond",
 *         "width": 10,
 *         "height": 5,
 *         "x": 0,
 *         "y": 0
 *     },
 *     "topShape": {
 *         "type": "Circle",
 *         "radius": 2,
 *         "x": 4,
 *         "y": 1
 *     }
 * }
 * ```
 *
 * Both the type field name (`"type"`) and the type labels (`"Rectangle"`)
 * are configurable.
 *
 * ## Registering Types
 *
 * Create a `RuntimeTypeAdapterFactory` by passing the base type and type field
 * name to the [of] factory method. If you don't supply an explicit type
 * field name, `"type"` will be used.
 * ```
 * val shapeAdapterFactory = RuntimeTypeAdapterFactory.of(Shape::class.java, "type")
 * ```
 *
 * Next register all of your subtypes. Every subtype must be explicitly
 * registered. This protects your application from injection attacks. If you
 * don't supply an explicit type label, the type's simple name will be used.
 * ```
 * shapeAdapterFactory.registerSubtype(Rectangle::class.java, "Rectangle")
 * shapeAdapterFactory.registerSubtype(Circle::class.java, "Circle")
 * shapeAdapterFactory.registerSubtype(Diamond::class.java, "Diamond")
 * ```
 *
 * Finally, register the type adapter factory in your application's GSON builder:
 * ```
 * val gson = GsonBuilder()
 *     .registerTypeAdapterFactory(shapeAdapterFactory)
 *     .create()
 * ```
 *
 * Like `GsonBuilder`, this API supports chaining:
 * ```
 * val shapeAdapterFactory = RuntimeTypeAdapterFactory.of(Shape::class.java)
 *     .registerSubtype(Rectangle::class.java)
 *     .registerSubtype(Circle::class.java)
 *     .registerSubtype(Diamond::class.java)
 * ```
 *
 * ## Serialization and deserialization
 *
 * In order to serialize and deserialize a polymorphic object,
 * you must specify the base type explicitly.
 * ```
 * val diamond = Diamond()
 * val json = gson.toJson(diamond, Shape::class.java)
 * ```
 * And then:
 * ```
 * val shape = gson.fromJson(json, Shape::class.java)
 * ```
 */
class RuntimeTypeAdapterFactory<T> private constructor(
    private val baseType: Class<*>,
    private val typeFieldName: String,
    private val maintainType: Boolean,
) : TypeAdapterFactory {

    private val labelToSubtype = LinkedHashMap<String, Class<*>>()
    private val subtypeToLabel = LinkedHashMap<Class<*>, String>()
    private var recognizeSubtypes = false

    /**
     * Ensures that this factory will handle not just the given `baseType`, but any subtype
     * of that type.
     */
    fun recognizeSubtypes(): RuntimeTypeAdapterFactory<T> {
        recognizeSubtypes = true
        return this
    }

    /**
     * Registers [type] identified by [label]. Labels are case sensitive.
     *
     * @throws IllegalArgumentException if either [type] or [label]
     *     have already been registered on this type adapter.
     */
    fun registerSubtype(type: Class<out T>, label: String): RuntimeTypeAdapterFactory<T> {
        requireNotNull(type)
        requireNotNull(label)
        require(!subtypeToLabel.containsKey(type) && !labelToSubtype.containsKey(label)) {
            "types and labels must be unique"
        }
        labelToSubtype[label] = type
        subtypeToLabel[type] = label
        return this
    }

    /**
     * Registers [type] identified by its [simple name][Class.getSimpleName].
     * Labels are case sensitive.
     *
     * @throws IllegalArgumentException if either [type] or its simple name
     *     have already been registered on this type adapter.
     */
    fun registerSubtype(type: Class<out T>): RuntimeTypeAdapterFactory<T> {
        return registerSubtype(type, type.simpleName)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any?> create(gson: Gson, type: TypeToken<R>): TypeAdapter<R>? {
        if (type == null) {
            return null
        }
        val rawType = type.rawType
        val handle = if (recognizeSubtypes) baseType.isAssignableFrom(rawType) else baseType == rawType
        if (!handle) {
            return null
        }

        val jsonElementAdapter = gson.getAdapter(JsonElement::class.java)
        val labelToDelegate = LinkedHashMap<String, TypeAdapter<*>>()
        val subtypeToDelegate = LinkedHashMap<Class<*>, TypeAdapter<*>>()
        for ((label, subtype) in labelToSubtype) {
            val delegate = gson.getDelegateAdapter(this, TypeToken.get(subtype))
            labelToDelegate[label] = delegate
            subtypeToDelegate[subtype] = delegate
        }

        return object : TypeAdapter<R>() {
            @Throws(IOException::class)
            override fun read(`in`: JsonReader): R {
                val jsonElement = jsonElementAdapter.read(`in`)
                val labelJsonElement: JsonElement? = if (maintainType) {
                    jsonElement.asJsonObject.get(typeFieldName)
                } else {
                    jsonElement.asJsonObject.remove(typeFieldName)
                }

                if (labelJsonElement == null) {
                    throw JsonParseException(
                        "cannot deserialize $baseType because it does not define a field named $typeFieldName"
                    )
                }
                val label = labelJsonElement.asString
                val delegate = labelToDelegate[label] as TypeAdapter<R>?
                    ?: throw JsonParseException(
                        "cannot deserialize $baseType subtype named $label; did you forget to register a subtype?"
                    )
                return delegate.fromJsonTree(jsonElement)
            }

            @Throws(IOException::class)
            override fun write(out: JsonWriter, value: R) {
                val srcType = value!!::class.java
                val label = subtypeToLabel[srcType]
                val delegate = subtypeToDelegate[srcType] as TypeAdapter<R>?
                    ?: throw JsonParseException(
                        "cannot serialize ${srcType.name}; did you forget to register a subtype?"
                    )
                val jsonObject = delegate.toJsonTree(value).asJsonObject

                if (maintainType) {
                    jsonElementAdapter.write(out, jsonObject)
                    return
                }

                val clone = JsonObject()

                if (jsonObject.has(typeFieldName)) {
                    throw JsonParseException(
                        "cannot serialize ${srcType.name} because it already defines a field named $typeFieldName"
                    )
                }
                clone.add(typeFieldName, JsonPrimitive(label))

                for ((key, jsonValue) in jsonObject.entrySet()) {
                    clone.add(key, jsonValue)
                }
                jsonElementAdapter.write(out, clone)
            }
        }.nullSafe()
    }

    companion object {
        /**
         * Creates a new runtime type adapter using for [baseType] using
         * [typeFieldName] as the type field name. Type field names are case sensitive.
         *
         * @param maintainType true if the type field should be included in deserialized objects
         */
        @JvmStatic
        @JvmOverloads
        fun <T> of(
            baseType: Class<T>,
            typeFieldName: String = "type",
            maintainType: Boolean = false,
        ): RuntimeTypeAdapterFactory<T> = RuntimeTypeAdapterFactory(baseType, typeFieldName, maintainType)
    }
}
