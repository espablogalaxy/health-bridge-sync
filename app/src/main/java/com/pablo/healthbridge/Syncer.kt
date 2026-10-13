package com.pablo.healthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

object Config {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun url(c: Context): String = p(c).getString("url", "http://192.168.1.5:8765")!!
    fun token(c: Context): String = p(c).getString("token", "")!!
    fun origin(c: Context): String = p(c).getString("origin", "com.sec.android.app.shealth")!!
    fun lastSync(c: Context): Long = p(c).getLong("lastSync", 0L)
    fun status(c: Context): String = p(c).getString("status", "Sin sincronizar todavía")!!

    fun save(c: Context, url: String, token: String, origin: String) {
        p(c).edit()
            .putString("url", url.trim().trimEnd('/'))
            .putString("token", token.trim())
            .putString("origin", origin.trim())
            .apply()
    }

    fun setLastSync(c: Context, v: Long) = p(c).edit().putLong("lastSync", v).apply()
    fun setStatus(c: Context, s: String) = p(c).edit().putString("status", s).apply()
}

object Syncer {
    val CORE: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    val ALL: Set<String> = CORE + setOf(
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
    )

    private fun rec(
        id: String,
        type: String,
        start: Instant,
        end: Instant,
        value: Double? = null,
        unit: String? = null,
        extra: JSONObject? = null,
    ): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("type", type)
        o.put("start", start.truncatedTo(ChronoUnit.SECONDS).toString())
        o.put("end", end.truncatedTo(ChronoUnit.SECONDS).toString())
        if (value != null) o.put("value", value)
        if (unit != null) o.put("unit", unit)
        if (extra != null) o.put("extra", extra)
        return o
    }

    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        type: KClass<T>,
        start: Instant,
        end: Instant,
        origin: Set<DataOrigin>,
    ): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        while (true) {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    dataOriginFilter = origin,
                    pageSize = 1000,
                    pageToken = token,
                )
            )
            out.addAll(resp.records)
            token = resp.pageToken
            if (token == null || resp.records.isEmpty()) break
        }
        return out
    }

    private fun post(base: String, token: String, batch: JSONArray): Int {
        val conn = URL("$base/ingest").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(batch.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("El servidor respondió HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).optInt("new", 0)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun run(ctx: Context): String {
        val base = Config.url(ctx)
        val token = Config.token(ctx)
        if (token.isBlank()) return "Falta el token"
        if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) {
            return "Health Connect no está disponible en este móvil"
        }
        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(CORE)) return "Faltan permisos de Health Connect"

        val originPkg = Config.origin(ctx)
        val origin: Set<DataOrigin> = if (originPkg.isBlank()) emptySet() else setOf(DataOrigin(originPkg))

        val now = Instant.now()
        val last = Config.lastSync(ctx)
        val start = if (last == 0L) now.minus(30, ChronoUnit.DAYS) else Instant.ofEpochMilli(last).minus(2, ChronoUnit.HOURS)

        val all = ArrayList<JSONObject>()

        for (r in readAll(client, StepsRecord::class, start, now, origin)) {
            all.add(rec(r.metadata.id, "steps", r.startTime, r.endTime, r.count.toDouble(), "count"))
        }

        for (r in readAll(client, SleepSessionRecord::class, start, now, origin)) {
            val stages = JSONArray()
            for (s in r.stages) {
                val so = JSONObject()
                so.put("stage", s.stage)
                so.put("start", s.startTime.truncatedTo(ChronoUnit.SECONDS).toString())
                so.put("end", s.endTime.truncatedTo(ChronoUnit.SECONDS).toString())
                stages.put(so)
            }
            val extra = JSONObject().put("stages", stages)
            all.add(rec(r.metadata.id, "sleep", r.startTime, r.endTime, null, null, extra))
        }

        for (r in readAll(client, HeartRateRecord::class, start, now, origin)) {
            r.samples.forEachIndexed { i, s ->
                all.add(rec("${r.metadata.id}#$i", "heart_rate", s.time, s.time, s.beatsPerMinute.toDouble(), "bpm"))
            }
        }

        for (r in readAll(client, WeightRecord::class, start, now, origin)) {
            all.add(rec(r.metadata.id, "weight", r.time, r.time, r.weight.inKilograms, "kg"))
        }

        for (r in readAll(client, ExerciseSessionRecord::class, start, now, origin)) {
            val extra = JSONObject()
            extra.put("exerciseType", r.exerciseType)
            if (r.title != null) extra.put("title", r.title)
            val agg = try {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(DistanceRecord.DISTANCE_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(r.startTime, r.endTime),
                        dataOriginFilter = origin,
                    )
                )
            } catch (e: Exception) {
                null
            }
            val dist = agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters
            val kcal = agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories
            if (dist != null) extra.put("distance_m", dist)
            if (kcal != null) extra.put("energy_kcal", kcal)
            all.add(rec(r.metadata.id, "workout", r.startTime, r.endTime, null, null, extra))
        }

        var newCount = 0
        var i = 0
        while (i < all.size) {
            val batch = JSONArray()
            for (j in i until minOf(i + 1000, all.size)) batch.put(all[j])
            newCount += post(base, token, batch)
            i += 1000
        }

        Config.setLastSync(ctx, now.toEpochMilli())
        return "Listo: ${all.size} registros leídos, $newCount nuevos en el servidor"
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            Config.setStatus(applicationContext, Syncer.run(applicationContext))
            Result.success()
        } catch (e: Exception) {
            Config.setStatus(applicationContext, "Error: ${e.message}")
            Result.retry()
        }
    }
}
