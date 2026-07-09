package com.example.nutrigeniusbridge

import androidx.health.connect.client.records.Record
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method

object HealthDataSerializer {

    fun serializeRecords(records: List<Record>): String {
        val array = JSONArray()
        for (record in records) {
            array.put(serializeObject(record))
        }
        return array.toString()
    }

    private fun serializeObject(obj: Any?): Any? {
        if (obj == null) return JSONObject.NULL

        val clazz = obj::class.java
        val name = clazz.simpleName

        // Handle Date/Time
        return when (name) {
            "Instant", "ZonedDateTime", "Duration", "LocalDateTime" -> obj.toString()
            "String", "Integer", "Double", "Long", "Float", "Boolean" -> obj
            else -> {
                if (obj is Number || obj is CharSequence || obj is Boolean) {
                    return obj
                }
                if (obj is Iterable<*>) {
                    val arr = JSONArray()
                    for (item in obj) {
                        arr.put(serializeObject(item))
                    }
                    return arr
                }
                if (obj is Map<*, *>) {
                    val mapObj = JSONObject()
                    for ((k, v) in obj) {
                        mapObj.put(k.toString(), serializeObject(v))
                    }
                    return mapObj
                }
                
                // Generic reflection for complex objects
                val jsonObject = JSONObject()
                for (method in clazz.methods) {
                    if (method.name.startsWith("get") && method.parameterCount == 0 && method.name != "getClass") {
                        val propName = method.name.removePrefix("get").replaceFirstChar { it.lowercase() }
                        try {
                            val value = method.invoke(obj)
                            jsonObject.put(propName, serializeObject(value))
                        } catch (e: Exception) {
                            // Skip inaccessible or failing properties
                        }
                    } else if (method.name.startsWith("is") && method.parameterCount == 0) {
                        val propName = method.name.removePrefix("is").replaceFirstChar { it.lowercase() }
                        try {
                            val value = method.invoke(obj)
                            jsonObject.put(propName, serializeObject(value))
                        } catch (e: Exception) {
                            // Skip inaccessible or failing properties
                        }
                    }
                }
                jsonObject
            }
        }
    }

    private fun tryMethod(obj: Any, methodName: String): Any? {
        return try {
            val method = obj::class.java.getMethod(methodName)
            method.invoke(obj)
        } catch (e: Exception) {
            null
        }
    }
}
