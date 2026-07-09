package com.example.nutrigeniusbridge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthConnectWebAppInterface(
    private val mainActivity: MainActivity,
    private val healthConnectManager: HealthConnectManager,
    private val webView: WebView,
    private val errorManager: ErrorManager
) {
    @JavascriptInterface
    fun getAvailableRecordTypes(daysBack: Int, callbackFunctionName: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val endTime = Instant.now()
            val startTime = endTime.minus(daysBack.toLong(), ChronoUnit.DAYS)
            
            val availableTypes = mutableListOf<String>()
            
            withContext(Dispatchers.IO) {
                for (recordName in healthConnectManager.recordTypesMap.keys) {
                    if (healthConnectManager.hasPermissions(recordName)) {
                        val data = healthConnectManager.readData(recordName, startTime, endTime)
                        if (data.isNotEmpty()) {
                            availableTypes.add(recordName)
                        }
                    }
                }
            }
            
            val jsonArray = org.json.JSONArray(availableTypes).toString()
            val jsCommand = "javascript:if(window.$callbackFunctionName) { window.$callbackFunctionName('$jsonArray'); } else { console.error('Callback function ' + '$callbackFunctionName' + ' not found.'); }"
            webView.evaluateJavascript(jsCommand, null)
        }
    }

    @JavascriptInterface
    fun readRecords(recordName: String, daysBack: Int, callbackFunctionName: String) {
        CoroutineScope(Dispatchers.Main).launch {
            if (healthConnectManager.hasPermissions(recordName)) {
                fetchAndReturnData(recordName, daysBack, callbackFunctionName)
            } else {
                // Return empty array silently. Auto-prompting is handled by checkAndRequestMissingPermissions
                val jsCommand = "javascript:if(window.$callbackFunctionName) { window.$callbackFunctionName('[]'); } else { console.error('Callback function ' + '$callbackFunctionName' + ' not found.'); }"
                webView.evaluateJavascript(jsCommand, null)
            }
        }
    }

    @JavascriptInterface
    fun checkAndRequestMissingPermissions(recordNamesJson: String, callbackFunctionName: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val recordNames = try {
                val array = org.json.JSONArray(recordNamesJson)
                List(array.length()) { array.getString(it) }
            } catch (e: Exception) {
                emptyList<String>()
            }

            val missingAndNeverRequested = mutableListOf<String>()
            for (recordName in recordNames) {
                if (!healthConnectManager.hasPermissions(recordName) && !healthConnectManager.hasBeenRequested(recordName)) {
                    missingAndNeverRequested.add(recordName)
                }
            }

            if (missingAndNeverRequested.isNotEmpty()) {
                mainActivity.requestSpecificPermissions(missingAndNeverRequested, callbackFunctionName)
                healthConnectManager.markAsRequested(missingAndNeverRequested)
            } else {
                // If nothing new to request, just return immediately
                val jsCommand = "javascript:if(window.$callbackFunctionName) { window.$callbackFunctionName(); }"
                webView.evaluateJavascript(jsCommand, null)
            }
        }
    }

    @JavascriptInterface
    fun getPermissionStatus(callbackFunctionName: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val statusMap = mutableMapOf<String, Boolean>()
            withContext(Dispatchers.IO) {
                for (recordName in healthConnectManager.recordTypesMap.keys) {
                    statusMap[recordName] = healthConnectManager.hasPermissions(recordName)
                }
            }
            val json = Gson().toJson(statusMap)
            val escapedJson = json.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
            val jsCommand = "javascript:if(window.$callbackFunctionName) { window.$callbackFunctionName('$escapedJson'); }"
            webView.evaluateJavascript(jsCommand, null)
        }
    }

    @JavascriptInterface
    fun requestSpecificPermissions(recordNamesJson: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val recordNames = try {
                val array = org.json.JSONArray(recordNamesJson)
                List(array.length()) { array.getString(it) }
            } catch (e: Exception) {
                emptyList<String>()
            }
            if (recordNames.isNotEmpty()) {
                mainActivity.requestSpecificPermissions(recordNames, null)
            }
        }
    }

    @JavascriptInterface
    fun requestAllPermissions() {
        CoroutineScope(Dispatchers.Main).launch {
            mainActivity.requestAllPermissions()
        }
    }

    @JavascriptInterface
    fun syncHydrationData(liters: Double, dateStr: String, callbackFunctionName: String) {
        CoroutineScope(Dispatchers.Main).launch {
            if (healthConnectManager.hasPermissions("HydrationRecord")) {
                withContext(Dispatchers.IO) {
                    healthConnectManager.syncHydrationData(liters, dateStr)
                }
                val jsCommand = "javascript:if(window.$callbackFunctionName) { window.$callbackFunctionName(); }"
                webView.evaluateJavascript(jsCommand, null)
            } else {
                val jsCommand = "javascript:if(window.$callbackFunctionName) { window.$callbackFunctionName('Permission denied'); }"
                webView.evaluateJavascript(jsCommand, null)
            }
        }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val clipboard = mainActivity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("HealthConnectData", text)
            clipboard.setPrimaryClip(clip)
            android.widget.Toast.makeText(mainActivity, "Copiato negli appunti!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun exportData(jsonData: String, filename: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val file = java.io.File(mainActivity.cacheDir, filename)
                file.writeText(jsonData)
                
                val uri = androidx.core.content.FileProvider.getUriForFile(mainActivity, "${mainActivity.packageName}.fileprovider", file)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                mainActivity.startActivity(android.content.Intent.createChooser(intent, "Esporta dati"))
            } catch (e: Exception) {
                errorManager.logError("Errore esportazione", e.stackTraceToString(), "HealthConnectWebAppInterface", true)
                android.widget.Toast.makeText(mainActivity, "Errore esportazione", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun readRecordsByTime(recordName: String, startTimeIso: String, endTimeIso: String, callbackFunctionName: String) {
        CoroutineScope(Dispatchers.Main).launch {
            if (healthConnectManager.hasPermissions(recordName)) {
                try {
                    val startTime = Instant.parse(startTimeIso)
                    val endTime = Instant.parse(endTimeIso)
                    fetchAndReturnDataChunked(recordName, startTime, endTime, callbackFunctionName)
                } catch (e: Exception) {
                    val jsCommand = "javascript:if(window.$callbackFunctionName) { window.$callbackFunctionName('[]'); }"
                    webView.evaluateJavascript(jsCommand, null)
                }
            } else {
                val jsCommand = "javascript:if(window.$callbackFunctionName) { window.$callbackFunctionName('[]'); }"
                webView.evaluateJavascript(jsCommand, null)
            }
        }
    }

    private suspend fun fetchAndReturnDataChunked(recordName: String, startTime: Instant, endTime: Instant, callbackFunctionName: String) {
        var parts = 1
        val maxParts = 5
        var success = false
        
        while (parts <= maxParts && !success) {
            try {
                val duration = ChronoUnit.MILLIS.between(startTime, endTime)
                val chunkDuration = duration / parts
                
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("javascript:window.temp_$callbackFunctionName = [];", null)
                }

                for (i in 0 until parts) {
                    val chunkStart = startTime.plus(chunkDuration * i, ChronoUnit.MILLIS)
                    val chunkEnd = if (i == parts - 1) endTime else startTime.plus(chunkDuration * (i + 1), ChronoUnit.MILLIS)
                    
                    val rawData = withContext(Dispatchers.IO) {
                        healthConnectManager.readData(recordName, chunkStart, chunkEnd)
                    }
                    
                    if (rawData.isNotEmpty()) {
                        val jsonData = withContext(Dispatchers.Default) {
                            HealthDataSerializer.serializeRecords(rawData)
                        }
                        
                        withContext(Dispatchers.Main) {
                            val escapedJson = jsonData.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
                            val jsCommand = "javascript:window.temp_$callbackFunctionName.push(...JSON.parse('$escapedJson'));"
                            webView.evaluateJavascript(jsCommand, null)
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    val jsCommand = "javascript:if(window.$callbackFunctionName) { window.$callbackFunctionName(JSON.stringify(window.temp_$callbackFunctionName)); delete window.temp_$callbackFunctionName; }"
                    webView.evaluateJavascript(jsCommand, null)
                }
                success = true
            } catch (e: OutOfMemoryError) {
                parts++
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("javascript:delete window.temp_$callbackFunctionName;", null)
                }
                System.gc()
            } catch (e: Exception) {
                errorManager.logError("Error fetching data chunked", e.stackTraceToString(), "HealthConnectWebAppInterface", false)
                break
            }
        }

        if (!success) {
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript("javascript:if(window.$callbackFunctionName) { window.$callbackFunctionName('[]'); }", null)
            }
        }
    }

    suspend fun fetchAndReturnData(recordName: String, daysBack: Int, callbackFunctionName: String) {
        val endTime = Instant.now()
        val startTime = endTime.minus(daysBack.toLong(), ChronoUnit.DAYS)

        val rawData = withContext(Dispatchers.IO) {
            healthConnectManager.readData(recordName, startTime, endTime)
        }

        val jsonData = withContext(Dispatchers.Default) {
            HealthDataSerializer.serializeRecords(rawData)
        }

        withContext(Dispatchers.Main) {
            val escapedJson = jsonData.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
            val jsCommand = "javascript:if(window.$callbackFunctionName) { window.$callbackFunctionName('$escapedJson'); } else { console.error('Callback function ' + '$callbackFunctionName' + ' not found.'); }"
            webView.evaluateJavascript(jsCommand, null)
        }
    }

    @JavascriptInterface
    fun minimizeApp() {
        mainActivity.moveTaskToBack(true)
    }

    @JavascriptInterface
    fun saveBlobFile(base64Data: String, fileName: String, mimeType: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val data = base64Data.substringAfter("base64,")
                val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = mainActivity.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    mainActivity.contentResolver.openOutputStream(uri)?.use {
                        it.write(bytes)
                    }
                    showDownloadNotification(fileName, mimeType, uri)
                } else {
                    errorManager.logError("Errore salvataggio file blob", "URI null ritornato dal ContentResolver", "HealthConnectWebAppInterface", true)
                    android.widget.Toast.makeText(mainActivity, "Errore salvataggio", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorManager.logError("Errore salvataggio file blob", e.stackTraceToString(), "HealthConnectWebAppInterface", true)
                android.widget.Toast.makeText(mainActivity, "Errore salvataggio file", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDownloadNotification(fileName: String, mimeType: String, uri: android.net.Uri) {
        val notificationManager = mainActivity.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "downloads"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Download", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }
        
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(mainActivity, uri.hashCode(), intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = androidx.core.app.NotificationCompat.Builder(mainActivity, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download completato")
            .setContentText(fileName)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
    @JavascriptInterface
    fun showNotification(title: String, body: String) {
        val notificationManager = mainActivity.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "water_reminders"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Promemoria Acqua", android.app.NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }
        
        val intent = android.content.Intent(mainActivity, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(mainActivity, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)

        val builder = androidx.core.app.NotificationCompat.Builder(mainActivity, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    @JavascriptInterface
    fun scheduleNotification(title: String, body: String, delayMs: Double) {
        val delay = delayMs.toLong()
        if (delay <= 0) {
            showNotification(title, body)
            return
        }
        
        val intent = android.content.Intent(mainActivity, NotificationReceiver::class.java).apply {
            putExtra("title", title)
            putExtra("body", body)
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(mainActivity, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        
        val alarmManager = mainActivity.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        val triggerAtMillis = System.currentTimeMillis() + delay
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            errorManager.logError("Permessi di allarme non concessi", e.stackTraceToString(), "HealthConnectWebAppInterface", true)
            alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    @JavascriptInterface
    fun cancelNotification() {
        val intent = android.content.Intent(mainActivity, NotificationReceiver::class.java)
        val pendingIntent = android.app.PendingIntent.getBroadcast(mainActivity, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = mainActivity.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.cancel(pendingIntent)
    }
}
