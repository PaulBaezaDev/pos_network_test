package com.example.pos_network_tester.simulation

import android.os.SystemClock
import com.example.pos_network_tester.logging.EventType
import com.example.pos_network_tester.logging.TestEventLogger
import com.example.pos_network_tester.network.ConnectivitySnapshot
import com.example.pos_network_tester.network.NetworkAction
import com.example.pos_network_tester.network.NetworkControl
import com.example.pos_network_tester.network.SwitchOutcome
import com.example.pos_network_tester.network.WifiProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Locale

data class ActiveRun(val testId: String, val scenario: Scenario, val startedAtMillis: Long)

data class ActionReport(val text: String, val isError: Boolean)

/** Próximo cambio programado de una prueba, en tiempo de [ScenarioController.clock]. */
data class Countdown(val targetElapsedMs: Long, val nextLabel: String)

/**
 * Coordina cambios rápidos de red y escenarios de prueba. Cada acción se
 * registra con un identificador de prueba y se considera confirmada solo
 * cuando la red alcanza el estado esperado con Internet real, no cuando el
 * comando termina.
 */
class ScenarioController(
    private val scope: CoroutineScope,
    private val network: NetworkControl,
    private val snapshot: StateFlow<ConnectivitySnapshot>,
    private val currentSsid: () -> String?,
    private val profiles: () -> List<WifiProfile>,
    private val config: () -> ScenarioConfig,
    val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {

    private val _activeRun = MutableStateFlow<ActiveRun?>(null)
    val activeRun: StateFlow<ActiveRun?> = _activeRun.asStateFlow()

    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    private val _pendingAction = MutableStateFlow<String?>(null)
    val pendingAction: StateFlow<String?> = _pendingAction.asStateFlow()

    private val _lastReport = MutableStateFlow<ActionReport?>(null)
    val lastReport: StateFlow<ActionReport?> = _lastReport.asStateFlow()

    private val _countdown = MutableStateFlow<Countdown?>(null)
    val countdown: StateFlow<Countdown?> = _countdown.asStateFlow()

    private var job: Job? = null

    /**
     * Última acción ejecutada cuyo efecto aún no se confirmó. Mientras exista,
     * el estado observado puede ser el anterior al comando, así que no se usa
     * para saltarse la siguiente acción.
     */
    @Volatile
    private var unconfirmedAction: NetworkAction? = null

    @Volatile
    private var shuttingDown = false

    fun restoreAction(): NetworkAction.RestoreAll =
        NetworkAction.RestoreAll(profiles().firstOrNull { it.isPrimary })

    /** Devuelve un motivo legible si no se puede iniciar, o null si arrancó. */
    fun startScenario(scenario: Scenario): String? {
        if (scenario.kind == ScenarioKind.REQUIRES_APP_INTEGRATION) {
            val reason = scenario.describe(config())
            log(EventType.INFO, "${scenario.label} no disponible: $reason", null, scenario.label)
            return reason
        }
        _activeRun.value?.let { return busyMessage(it) }
        if (!network.isReady) {
            val reason = "${scenario.label} cambia la red real y requiere Shizuku (${network.readinessLabel})"
            log(EventType.INFO, reason, null, scenario.label)
            return reason
        }
        if (scenario == Scenario.NETWORK_ROTATION && rotationSequence().size < 2) {
            return "La rotación necesita al menos 2 destinos: perfiles Wi-Fi o una SIM lista."
        }

        val run = ActiveRun(newTestId("T"), scenario, System.currentTimeMillis())
        _activeRun.value = run
        var started = false
        val runJob = launchExclusive {
            started = true
            log(EventType.TEST_START, scenario.describe(config()), run)
            if (restoreAction().primary == null) {
                log(EventType.INFO, "Sin red principal: al restaurar se reactiva el Wi-Fi y Android elige la red guardada.", run)
            }
            var outcome = "Prueba detenida"
            try {
                runScenario(run)
                outcome = "Prueba completada"
            } catch (e: ActionNotExecuted) {
                outcome = "Prueba abortada: ${e.message}"
            } finally {
                log(EventType.TEST_END, outcome, run)
            }
        }
        // Se limpia al terminar el job pase lo que pase, incluso si se cancela antes de arrancar:
        // así una prueba nunca queda "pegada" bloqueando el resto de acciones.
        runJob.invokeOnCompletion {
            if (!started) log(EventType.TEST_END, "Prueba detenida antes de iniciar", run)
            _countdown.value = null
            _stopping.value = false
            _activeRun.compareAndSet(run, null)
        }
        return null
    }

    fun stopScenario() {
        val run = _activeRun.value ?: return
        if (_stopping.value) return
        val current = job
        if (current == null || current.isCompleted) {
            _activeRun.compareAndSet(run, null)
            return
        }
        _stopping.value = true
        log(EventType.INFO, "Detención solicitada: restaurando la red", run)
        current.cancel()
    }

    /** Cambio inmediato desde el panel. Un nuevo cambio reemplaza al que esté esperando confirmación. */
    fun quickSwitch(action: NetworkAction): String? {
        _activeRun.value?.let { return busyMessage(it) }
        val testId = newTestId("Q")
        launchExclusive { perform(action, testId, QUICK_SWITCH_LABEL) }
        return null
    }

    /** Al cerrar el panel: detiene la prueba y restaura la red sin esperar confirmación. */
    fun shutdown() {
        shuttingDown = true
        job?.cancel()
    }

    private fun busyMessage(run: ActiveRun): String =
        if (_stopping.value) "Deteniendo \"${run.scenario.label}\"…"
        else "Detén primero la prueba \"${run.scenario.label}\""

    private fun launchExclusive(block: suspend CoroutineScope.() -> Unit): Job {
        val previous = job
        return scope.launch {
            previous?.cancelAndJoin()
            block()
        }.also { job = it }
    }

    private suspend fun runScenario(run: ActiveRun) {
        when (run.scenario) {
            Scenario.NORMAL -> perform(restoreAction(), run)

            Scenario.TEMPORARY_LOSS -> try {
                perform(NetworkAction.NoNetwork, run)
                timedWait(ScenarioTimings.TEMPORARY_LOSS_MS, restoreAction().shortLabel)
            } finally {
                restore(run)
            }

            Scenario.DROP_DURING_OPERATION -> try {
                perform(NetworkAction.NoNetwork, run)
                log(EventType.INFO, "Corte aplicado. Ejecuta ahora la operación de prueba en la app de pagos.", run)
                awaitCancellation()
            } finally {
                restore(run)
                log(EventType.RESULT, UNKNOWN_OUTCOME_NOTICE, run)
            }

            Scenario.RECOVERY -> {
                val recoveryTimes = mutableListOf<Long?>()
                try {
                    repeat(ScenarioTimings.RECOVERY_CYCLES) { cycle ->
                        log(EventType.INFO, "Ciclo ${cycle + 1}/${ScenarioTimings.RECOVERY_CYCLES}", run)
                        try {
                            perform(NetworkAction.NoNetwork, run)
                            timedWait(ScenarioTimings.RECOVERY_CUT_MS, restoreAction().shortLabel)
                        } finally {
                            recoveryTimes += restore(run)
                        }
                        if (cycle < ScenarioTimings.RECOVERY_CYCLES - 1) {
                            timedWait(ScenarioTimings.RECOVERY_PAUSE_MS, NetworkAction.NoNetwork.shortLabel)
                        }
                    }
                } finally {
                    log(EventType.RESULT, summarize("Recuperación", recoveryTimes), run)
                }
            }

            Scenario.INTERMITTENCE -> {
                val settings = config()
                val onMs = settings.intermittenceOnSeconds * 1000L
                val offMs = settings.intermittenceOffSeconds * 1000L
                val reconnectTimes = mutableListOf<Long?>()
                try {
                    timedWait(onMs, NetworkAction.NoNetwork.shortLabel)
                    var cycle = 0
                    while (true) {
                        cycle++
                        log(EventType.INFO, "Ciclo $cycle: corte de ${settings.intermittenceOffSeconds} s", run)
                        timedPhase(run, NetworkAction.NoNetwork, offMs, restoreAction().shortLabel)
                        reconnectTimes += timedPhase(run, restoreAction(), onMs, NetworkAction.NoNetwork.shortLabel)
                    }
                } finally {
                    restore(run)
                    log(EventType.RESULT, summarize("Intermitencia, reconexión", reconnectTimes), run)
                }
            }

            Scenario.NETWORK_ROTATION -> try {
                val intervalMs = config().rotationIntervalSeconds * 1000L
                while (true) {
                    val sequence = rotationSequence()
                    if (sequence.isEmpty()) break
                    sequence.forEachIndexed { index, action ->
                        perform(action, run)
                        timedWait(intervalMs, sequence[(index + 1) % sequence.size].shortLabel)
                    }
                }
            } finally {
                restore(run)
            }

            Scenario.SLOW_LATENCY, Scenario.REQUEST_TIMEOUT -> Unit
        }
    }

    private suspend fun timedWait(durationMs: Long, nextLabel: String) {
        _countdown.value = Countdown(clock() + durationMs, nextLabel)
        try {
            delay(durationMs)
        } finally {
            _countdown.value = null
        }
    }

    /** Ejecuta la acción y completa la fase con cadencia exacta; la confirmación se espera dentro de la misma fase. */
    private suspend fun timedPhase(run: ActiveRun, action: NetworkAction, durationMs: Long, nextLabel: String): Long? {
        val target = clock() + durationMs
        _countdown.value = Countdown(target, nextLabel)
        try {
            val confirmedIn = perform(action, run, confirmTimeoutMs = durationMs)
            val remaining = target - clock()
            if (remaining > 0) delay(remaining)
            return confirmedIn
        } finally {
            _countdown.value = null
        }
    }

    /**
     * Restaurar nunca aborta. Si la prueba se detuvo, se ejecutan los comandos
     * sin esperar confirmación para que "Detener" responda al instante; si
     * terminó sola, se mide la recuperación (y esa espera sí se puede detener).
     */
    private suspend fun restore(run: ActiveRun): Long? {
        val action = restoreAction()
        return if (!currentCoroutineContext().isActive || shuttingDown) {
            withContext(NonCancellable) { perform(action, run.testId, run.scenario.label, measure = false) }
        } else {
            perform(action, run.testId, run.scenario.label)
        }
    }

    /** Dentro de un escenario, una acción que no llega a ejecutarse aborta la prueba en vez de continuar. */
    private suspend fun perform(
        action: NetworkAction,
        run: ActiveRun,
        confirmTimeoutMs: Long = ScenarioTimings.STATE_TIMEOUT_MS
    ): Long? = perform(
        action,
        run.testId,
        run.scenario.label,
        confirmTimeoutMs = confirmTimeoutMs,
        abortIfNotExecuted = true
    )

    /** Ejecuta la acción y devuelve los ms hasta confirmar el estado esperado, o null si no se confirmó. */
    private suspend fun perform(
        action: NetworkAction,
        testId: String,
        scenarioLabel: String,
        measure: Boolean = true,
        confirmTimeoutMs: Long = ScenarioTimings.STATE_TIMEOUT_MS,
        abortIfNotExecuted: Boolean = false
    ): Long? {
        if (unconfirmedAction == null && network.isExpectedState(action, snapshot.value)) {
            log(EventType.RESULT, "${action.label}: ya estaba en ese estado", testId, scenarioLabel)
            _lastReport.value = ActionReport("${action.label}: ya activo", isError = false)
            return 0
        }
        if (action == NetworkAction.MobileOnly && !snapshot.value.status.simReady) {
            log(EventType.INFO, "Aviso: SIM no lista, los datos móviles no podrán conectar", testId, scenarioLabel)
        }

        log(EventType.NETWORK_ACTION, "Ejecutando: ${action.label}", testId, scenarioLabel)
        _pendingAction.value = action.label
        val startedAt = clock()
        try {
            return coroutineScope {
                // Se suscribe antes de ejecutar para no perder cambios que ocurran durante el comando.
                val confirmation = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(confirmTimeoutMs) {
                        snapshot.drop(1).first { network.isExpectedState(action, it) }
                    }
                }
                // Atómico: una acción de varios pasos (datos + Wi-Fi) nunca queda a medias.
                val outcome = withContext(NonCancellable) {
                    network.perform(action).also { if (it == SwitchOutcome.Executed) unconfirmedAction = action }
                }
                when (outcome) {
                    SwitchOutcome.Executed -> Unit
                    is SwitchOutcome.Failed -> {
                        confirmation.cancel()
                        reportError(action, "falló: ${outcome.reason}", testId, scenarioLabel)
                        if (abortIfNotExecuted) throw ActionNotExecuted("${action.label} falló")
                        return@coroutineScope null
                    }
                    is SwitchOutcome.RequiresAdb -> {
                        confirmation.cancel()
                        val commands = outcome.adbCommands.joinToString(" ; ")
                        reportError(action, "${outcome.reason} $commands", testId, scenarioLabel)
                        if (abortIfNotExecuted) throw ActionNotExecuted("${action.label} requiere ADB/Shizuku")
                        return@coroutineScope null
                    }
                }
                if (!measure) {
                    confirmation.cancel()
                    return@coroutineScope null
                }

                val confirmed = confirmation.await() != null
                val elapsed = clock() - startedAt
                if (confirmed) {
                    unconfirmedAction = null
                    log(EventType.RESULT, "${action.label}: confirmado en ${formatMs(elapsed)}", testId, scenarioLabel)
                    _lastReport.value = ActionReport("${action.label} en ${formatMs(elapsed)}", isError = false)
                    elapsed
                } else {
                    val now = snapshot.value
                    val detail = "no confirmado tras ${formatMs(confirmTimeoutMs)} " +
                        "(red activa: ${now.status.activeTransport.label}, Internet real: ${now.internetLabel()})"
                    reportError(action, detail, testId, scenarioLabel)
                    null
                }
            }
        } catch (e: CancellationException) {
            log(EventType.RESULT, "${action.label}: interrumpido antes de confirmar", testId, scenarioLabel)
            throw e
        } finally {
            _pendingAction.compareAndSet(action.label, null)
        }
    }

    private fun reportError(action: NetworkAction, detail: String, testId: String, scenarioLabel: String) {
        log(EventType.ERROR, "${action.label}: $detail", testId, scenarioLabel)
        _lastReport.value = ActionReport("${action.label}: $detail", isError = true)
    }

    private fun rotationSequence(): List<NetworkAction> =
        profiles().map { NetworkAction.ConnectWifi(it) } +
            if (snapshot.value.status.simReady) listOf(NetworkAction.MobileOnly) else emptyList()

    private fun summarize(title: String, times: List<Long?>): String {
        if (times.isEmpty()) return "$title: ningún ciclo completado"
        val confirmed = times.filterNotNull()
        if (confirmed.isEmpty()) return "$title: ningún ciclo confirmó Internet real (${times.size} intentos)"
        val failed = times.size - confirmed.size
        return "$title (${confirmed.size}/${times.size} confirmados): " +
            "mín ${formatMs(confirmed.min())}, prom ${formatMs(confirmed.average().toLong())}, " +
            "máx ${formatMs(confirmed.max())}" +
            if (failed > 0) "; $failed sin confirmar" else ""
    }

    private fun log(type: EventType, message: String, run: ActiveRun) =
        log(type, message, run.testId, run.scenario.label)

    private fun log(type: EventType, message: String, testId: String?, scenarioLabel: String?) {
        TestEventLogger.log(type, snapshot.value, currentSsid(), message, testId, scenarioLabel)
    }

    private fun newTestId(prefix: String): String =
        "$prefix-" + SimpleDateFormat("yyMMdd-HHmmss-SSS", Locale.US).format(System.currentTimeMillis())

    private fun formatMs(ms: Long): String = String.format(Locale.US, "%.1f s", ms / 1000.0)

    private class ActionNotExecuted(message: String) : Exception(message)

    private companion object {
        const val QUICK_SWITCH_LABEL = "Cambio rápido"
        const val UNKNOWN_OUTCOME_NOTICE =
            "Si había una operación en curso, su resultado es DESCONOCIDO: un timeout no prueba que " +
                "haya fallado. Verifica con el sistema de pagos o la conciliación antes de reintentar o anular."
    }
}
