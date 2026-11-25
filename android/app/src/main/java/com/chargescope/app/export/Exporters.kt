package com.chargescope.app.export

import android.content.Context
import android.net.Uri
import com.chargescope.app.data.Repository
import com.chargescope.app.domain.model.AnalysisBundle
import com.chargescope.app.domain.model.ChargingSnapshot
import com.google.gson.GsonBuilder
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Local export only. No upload. Caller must show the privacy warning before
 * invoking any of these functions.
 */
class Exporters(private val context: Context, private val repository: Repository) {

    val privacyNotice: String =
        "导出文件可能包含设备型号、系统版本、使用时间与充电行为数据。请仅在可信环境分享。"

    suspend fun exportCsv(sessionId: Long, uri: Uri, redact: Boolean = true) {
        val snapshots = repository.snapshotsFor(sessionId)
        val gson = GsonBuilder().create()
        context.contentResolver.openOutputStream(uri)?.use { out ->
            BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                w.write(CSV_HEADER)
                w.newLine()
                snapshots.forEach { s ->
                    w.write(listOf(
                        s.timestampElapsedMs.toString(),
                        s.wallClockTimeMs.toString(),
                        s.socPercent?.toString().orEmpty(),
                        s.batteryVoltageUv?.toString().orEmpty(),
                        s.batteryCurrentUa?.toString().orEmpty(),
                        s.batteryPowerUw?.toString().orEmpty(),
                        s.batteryTemperatureDecic?.toString().orEmpty(),
                        s.inputVoltageUv?.toString().orEmpty(),
                        s.inputCurrentUa?.toString().orEmpty(),
                        s.inputPowerUw?.toString().orEmpty(),
                        s.usbType.orEmpty(),
                        s.chargeType.orEmpty(),
                        s.chargingStatus.orEmpty(),
                        s.rootAvailable.toString(),
                        gson.toJson(s.sourceRecords.map {
                            if (redact) it.copy(sourcePath = it.sourcePath?.replace(Regex("/[0-9]+/"), "/<id>/")) else it
                        }),
                    ).joinToString(",") { csvEscape(it) })
                    w.newLine()
                }
            }
        } ?: throw IllegalStateException("无法打开输出 URI")
    }

    suspend fun exportJson(sessionId: Long, uri: Uri, bundle: AnalysisBundle?, redact: Boolean = true) {
        val snapshots = repository.snapshotsFor(sessionId)
        val payload = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "exportedAt" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
                .format(java.util.Date()),
            "privacy" to if (redact) "paths-redacted" else "raw",
            "device" to mapOf(
                "model" to if (redact) "<redacted>" else android.os.Build.MODEL,
                "androidVersion" to if (redact) "<redacted>" else android.os.Build.VERSION.RELEASE,
            ),
            "snapshots" to snapshots.map { s ->
                if (!redact) {
                    s
                } else {
                    s.copy(sourceRecords = s.sourceRecords.map {
                        it.copy(sourcePath = it.sourcePath?.replace(Regex("/[0-9]+/"), "/<id>/"))
                    })
                }
            },
            "analysis" to (bundle?.analysis ?: "unavailable"),
        )
        val json = GsonBuilder().setPrettyPrinting().create().toJson(payload)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("无法打开输出 URI")
    }

    private fun csvEscape(value: String): String {
        val needsQuote = value.contains(',') || value.contains('"') || value.contains('\n')
        return if (needsQuote) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    companion object {
        private const val CSV_HEADER =
            "timestamp_elapsed_ms,wall_clock_ms,soc_percent,battery_voltage_uv,battery_current_ua," +
                "battery_power_uw,battery_temperature_decic,input_voltage_uv,input_current_ua," +
                "input_power_uw,usb_type,charge_type,charging_status,root_available,source_records_json"
    }
}
