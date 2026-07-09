package com.example.nutrigeniusbridge

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ErrorRecord(
    val timestamp: String,
    val message: String,
    val details: String,
    val source: String,
    val isHandled: Boolean,
    val lastAction: String,
    val currentScreen: String
)

class ErrorManager(private val context: Context) {
    private val gson = Gson()
    private val errorsFile = File(context.filesDir, "errors.json")
    private val prefs = context.getSharedPreferences("error_prefs", Context.MODE_PRIVATE)

    var maxErrors: Int
        get() = prefs.getInt("max_errors", 50)
        set(value) {
            prefs.edit().putInt("max_errors", value).apply()
            trimErrors()
        }

    @Synchronized
    fun logError(
        message: String,
        details: String,
        source: String,
        isHandled: Boolean,
        lastAction: String = "",
        currentScreen: String = ""
    ) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val record = ErrorRecord(timestamp, message, details, source, isHandled, lastAction, currentScreen)
            val errors = getErrors().toMutableList()
            errors.add(0, record)
            saveErrors(errors)
        } catch (e: Exception) {
            // Se fallisce il log degli errori, non possiamo fare molto altro per non creare loop infiniti
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getErrors(): List<ErrorRecord> {
        if (!errorsFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<ErrorRecord>>() {}.type
            val content = errorsFile.readText()
            gson.fromJson(content, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clearErrors() {
        if (errorsFile.exists()) {
            errorsFile.delete()
        }
    }

    private fun saveErrors(errors: List<ErrorRecord>) {
        var trimmed = errors
        val max = maxErrors
        if (trimmed.size > max) {
            trimmed = trimmed.take(max)
        }
        errorsFile.writeText(gson.toJson(trimmed))
    }

    private fun trimErrors() {
        saveErrors(getErrors())
    }
}
