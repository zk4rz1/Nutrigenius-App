package com.example.nutrigeniusbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

class HealthConnectManager(private val context: Context) {
    val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }
    private val prefs by lazy { context.getSharedPreferences("HealthConnectPrefs", Context.MODE_PRIVATE) }

    val recordTypesMap: Map<String, KClass<out Record>> = mapOf(
        "WeightRecord" to WeightRecord::class,
        "BodyFatRecord" to BodyFatRecord::class,
        "HeartRateRecord" to HeartRateRecord::class,
        "TotalCaloriesBurnedRecord" to TotalCaloriesBurnedRecord::class,
        "DistanceRecord" to DistanceRecord::class,
        "ExerciseSessionRecord" to ExerciseSessionRecord::class,
        "StepsRecord" to StepsRecord::class,
        "HydrationRecord" to HydrationRecord::class,
        "PowerRecord" to PowerRecord::class,
        "SpeedRecord" to SpeedRecord::class,
        "Vo2MaxRecord" to Vo2MaxRecord::class,
        "HeightRecord" to HeightRecord::class,
        "BasalMetabolicRateRecord" to BasalMetabolicRateRecord::class,
        "BloodPressureRecord" to BloodPressureRecord::class,
        "BodyWaterMassRecord" to BodyWaterMassRecord::class,
        "LeanBodyMassRecord" to LeanBodyMassRecord::class,
        "BoneMassRecord" to BoneMassRecord::class
    )

    fun getPermissionsForRecord(recordName: String): Set<String> {
        val recordClass = recordTypesMap[recordName] ?: return emptySet()
        val perms = mutableSetOf(HealthPermission.getReadPermission(recordClass))
        if (recordName == "HydrationRecord") {
            perms.add(HealthPermission.getWritePermission(recordClass))
        }
        return perms
    }

    fun hasBeenRequested(recordName: String): Boolean {
        val requestedSet = prefs.getStringSet("requested_permissions", emptySet()) ?: emptySet()
        return requestedSet.contains(recordName)
    }

    fun markAsRequested(recordNames: List<String>) {
        val requestedSet = prefs.getStringSet("requested_permissions", emptySet())?.toMutableSet() ?: mutableSetOf()
        requestedSet.addAll(recordNames)
        prefs.edit().putStringSet("requested_permissions", requestedSet).apply()
    }

    suspend fun hasPermissions(recordName: String): Boolean {
        val required = getPermissionsForRecord(recordName)
        if (required.isEmpty()) return false
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return granted.containsAll(required)
    }

    suspend fun readData(recordName: String, startTime: Instant, endTime: Instant): List<Record> {
        val recordClass = recordTypesMap[recordName] ?: return emptyList()
        val timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
        
        return try {
            val allRecords = mutableListOf<Record>()
            var pageToken: String? = null
            do {
                val request = ReadRecordsRequest(
                    recordType = recordClass,
                    timeRangeFilter = timeRangeFilter,
                    pageToken = pageToken
                )
                val response = healthConnectClient.readRecords(request)
                allRecords.addAll(response.records)
                pageToken = response.pageToken
            } while (pageToken != null)
            allRecords
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun syncHydrationData(liters: Double, dateStr: String) {
        try {
            // dateStr is YYYY-MM-DD. We will delete all HydrationRecord for that day inserted by this app
            val formatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
            val date = java.time.LocalDate.parse(dateStr, formatter)
            val startOfDay = date.atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant()
            val endOfDay = date.plusDays(1).atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant()

            val timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay)
            healthConnectClient.deleteRecords(HydrationRecord::class, timeRangeFilter)

            if (liters > 0) {
                // Determine a time to insert. Let's insert at 12:00 PM of that day.
                val insertTime = date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()
                val record = HydrationRecord(
                    startTime = insertTime,
                    endTime = insertTime.plus(1, java.time.temporal.ChronoUnit.MINUTES),
                    volume = androidx.health.connect.client.units.Volume.liters(liters),
                    startZoneOffset = java.time.ZoneId.systemDefault().rules.getOffset(insertTime),
                    endZoneOffset = java.time.ZoneId.systemDefault().rules.getOffset(insertTime.plus(1, java.time.temporal.ChronoUnit.MINUTES)),
                    metadata = androidx.health.connect.client.records.metadata.Metadata.manualEntry()
                )
                healthConnectClient.insertRecords(listOf(record))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
