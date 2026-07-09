package com.example.nutrigeniusbridge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NutriGeniusErrorBridge(
    private val errorManager: ErrorManager,
    private val webView: WebView
) {
    @JavascriptInterface
    fun getErrors(callbackName: String) {
        val json = Gson().toJson(errorManager.getErrors())
        val escaped = json.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
        CoroutineScope(Dispatchers.Main).launch {
            webView.evaluateJavascript("javascript:if(window.$callbackName) { window.$callbackName('$escaped'); }", null)
        }
    }

    @JavascriptInterface
    fun clearErrors() {
        errorManager.clearErrors()
    }

    @JavascriptInterface
    fun setMaxErrors(max: Int) {
        errorManager.maxErrors = max
    }

    @JavascriptInterface
    fun getMaxErrors(): Int {
        return errorManager.maxErrors
    }

    @JavascriptInterface
    fun logJsError(message: String, source: String, lineno: Int, colno: Int, error: String, isHandled: Boolean, lastAction: String, currentScreen: String) {
        val details = "Riga $lineno, Colonna $colno\nStack: $error"
        errorManager.logError(message, details, source, isHandled, lastAction, currentScreen)
    }
}
