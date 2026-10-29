package com.example.pos_network_tester.simulation

enum class ScenarioKind {
    /** Cambia la red real del POS mediante Shizuku; afecta a todas las apps. */
    REAL_NETWORK,

    /** No se puede provocar a nivel de sistema sin root: requiere el modo de pruebas de la app de pagos. */
    REQUIRES_APP_INTEGRATION
}

/** Tiempos configurables por el usuario (en segundos). */
data class ScenarioConfig(
    val intermittenceOffSeconds: Int = 10,
    val intermittenceOnSeconds: Int = 10,
    val rotationIntervalSeconds: Int = 20
) {
    companion object {
        const val MIN_SECONDS = 3
        const val MAX_SECONDS = 600
    }
}

enum class Scenario(val label: String, val shortLabel: String, val kind: ScenarioKind) {
    NORMAL("Conectividad normal", "Normal", ScenarioKind.REAL_NETWORK),
    SLOW_LATENCY("Latencia elevada", "Latencia", ScenarioKind.REQUIRES_APP_INTEGRATION),
    REQUEST_TIMEOUT("Timeout de solicitud", "Timeout", ScenarioKind.REQUIRES_APP_INTEGRATION),
    TEMPORARY_LOSS("Pérdida temporal", "Pérdida 15 s", ScenarioKind.REAL_NETWORK),
    DROP_DURING_OPERATION("Corte durante operación", "Corte manual", ScenarioKind.REAL_NETWORK),
    RECOVERY("Recuperación tras corte", "Recuperación", ScenarioKind.REAL_NETWORK),
    INTERMITTENCE("Intermitencia", "Intermitencia", ScenarioKind.REAL_NETWORK),
    NETWORK_ROTATION("Rotación de redes", "Rotación", ScenarioKind.REAL_NETWORK);

    fun describe(config: ScenarioConfig): String = when (this) {
        NORMAL -> "Activa datos, vuelve a la red principal y espera Internet real (línea base)."
        SLOW_LATENCY, REQUEST_TIMEOUT ->
            "Sin root no se puede degradar la red del POS. Requiere el modo de pruebas en la capa Retrofit de la app de pagos."
        TEMPORARY_LOSS ->
            "Apaga Wi-Fi y datos durante ${ScenarioTimings.TEMPORARY_LOSS_MS / 1000} s, vuelve a la red principal y mide la recuperación."
        DROP_DURING_OPERATION -> "Corta Wi-Fi y datos al iniciar y mantiene el corte hasta que pulses Detener."
        RECOVERY ->
            "${ScenarioTimings.RECOVERY_CYCLES} ciclos de corte de ${ScenarioTimings.RECOVERY_CUT_MS / 1000} s; mide cuánto tarda en volver Internet."
        INTERMITTENCE ->
            "En bucle: ${config.intermittenceOnSeconds} s conectado y ${config.intermittenceOffSeconds} s sin red, " +
                "volviendo a la red principal, hasta que pulses Detener."
        NETWORK_ROTATION ->
            "Recorre los perfiles Wi-Fi y los datos móviles cada ${config.rotationIntervalSeconds} s hasta que pulses Detener."
    }
}

object ScenarioTimings {
    const val TEMPORARY_LOSS_MS = 15_000L
    const val RECOVERY_CUT_MS = 5_000L
    const val RECOVERY_CYCLES = 3
    const val RECOVERY_PAUSE_MS = 5_000L
    const val STATE_TIMEOUT_MS = 45_000L
}
