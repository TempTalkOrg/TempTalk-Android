package com.difft.android.chat.jobs















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



















































































































class RuntimeTypeAdapterFactory<T> private constructor(
    private val baseType: Class<*>,
    private val typeFieldName: String,
    private val maintainType: Boolean,
) : TypeAdapterFactory {

    private val labelToSubtype = LinkedHashMap<String, Class<*>>()
    private val subtypeToLabel = LinkedHashMap<Class<*>, String>()
    private var recognizeSubtypes = false

    



    fun recognizeSubtypes(): RuntimeTypeAdapterFactory<T> {
        recognizeSubtypes = true
        return this
    }

    





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
        





        @JvmStatic
        @JvmOverloads
        fun <T> of(
            baseType: Class<T>,
            typeFieldName: String = "type",
            maintainType: Boolean = false,
        ): RuntimeTypeAdapterFactory<T> = RuntimeTypeAdapterFactory(baseType, typeFieldName, maintainType)
    }
}
