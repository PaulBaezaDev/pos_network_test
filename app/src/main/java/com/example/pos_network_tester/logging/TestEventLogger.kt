package com.example.pos_network_tester.logging

import android.content.Context
import com.example.pos_network_tester.network.ConnectivitySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

enum class EventType { INFO, CONNECTIVITY_CHANGE, NETWORK_ACTION, RESULT, TEST_START, TEST_END, ERROR }

/**
 * Evento de diagnóstico. Deliberadamente no tiene ningún campo para payloads,
 * tokens, tarjetas o datos de cliente: solo metadatos de red y de la prueba.
 */
data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val testId: String? = null,
    val scenarioLabel: String? = null,
    val eventType: EventType,
    val wifiState: String,
    val mobileState: String,
    val mobileNetworkType: String,
    val internetState: String = "",
    val message: String
)

/**
 * Registro en memoria de los eventos de prueba, consultable desde la UI y
 * exportable a CSV. Vive a nivel de proceso para que tanto MainActivity como
 * FloatingPanelService escriban en el mismo historial.
 */
object TestEventLogger {

    private const val MAX_ENTRIES = 1000

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(
        eventType: EventType,
        wifiState: String,
        mobileState: String,
        mobileNetworkType: String,
        message: String,
        testId: String? = null,
        scenarioLabel: String? = null,
        internetState: String = ""
    ) {
        val entry = LogEntry(
            testId = testId,
            scenarioLabel = scenarioLabel,
            eventType = eventType,
            wifiState = wifiState,
            mobileState = mobileState,
            mobileNetworkType = mobileNetworkType,
            internetState = internetState,
            message = message
        )
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }

    fun log(
        eventType: EventType,
        snapshot: ConnectivitySnapshot,
        ssid: String?,
        message: String,
        testId: String? = null,
        scenarioLabel: String? = null
    ) = log(
        eventType = eventType,
        wifiState = snapshot.status.wifiLabel(ssid),
        mobileState = snapshot.status.mobileLabel(),
        mobileNetworkType = snapshot.status.mobileGeneration.label,
        message = message,
        testId = testId,
        scenarioLabel = scenarioLabel,
        internetState = snapshot.internetLabel()
    )

    fun clear() {
        _entries.value = emptyList()
    }

    fun exportCsv(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val file = File(dir, "pos_network_tester_${dateFormat.format(System.currentTimeMillis())}.csv")
        val rowFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        file.bufferedWriter().use { writer ->
            writer.appendLine(
                "timestamp,test_id,scenario,event_type,wifi_state,mobile_state,mobile_network_type,internet,message"
            )
            _entries.value.forEach { entry ->
                writer.appendLine(
                    listOf(
                        rowFormat.format(entry.timestampMillis),
                        entry.testId.orEmpty(),
                        entry.scenarioLabel.orEmpty(),
                        entry.eventType.name,
                        entry.wifiState,
                        entry.mobileState,
                        entry.mobileNetworkType,
                        entry.internetState,
                        entry.message
                    ).joinToString(",") { field -> csvEscape(field) }
                )
            }
        }
        return file
    }

    private fun csvEscape(field: String): String {
        val mustQuote = field.contains(",") || field.contains("\"") || field.contains("\n")
        if (!mustQuote) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
}
