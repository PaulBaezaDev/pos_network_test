package com.example.pos_network_tester.simulation

import com.example.pos_network_tester.logging.EventType
import com.example.pos_network_tester.logging.TestEventLogger
import com.example.pos_network_tester.network.ConnectivitySnapshot
import com.example.pos_network_tester.network.NetworkAction
import com.example.pos_network_tester.network.NetworkControl
import com.example.pos_network_tester.network.NetworkStatus
import com.example.pos_network_tester.network.ProbeResult
import com.example.pos_network_tester.network.SwitchOutcome
import com.example.pos_network_tester.network.Transport
import com.example.pos_network_tester.network.WifiProfile
import com.example.pos_network_tester.network.WifiSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScenarioControllerTest {

    private val primary = WifiProfile(ssid = "Principal", security = WifiSecurity.WPA2, passphrase = "x", isPrimary = true)
    private val secondary = WifiProfile(ssid = "Secundaria", security = WifiSecurity.WPA2, passphrase = "y")

    @Before
    fun clearLog() = TestEventLogger.clear()

    @Test
    fun `detener mientras espera una accion lenta anterior no deja la prueba pegada`() = runTest {
        val env = env(this)
        env.network.executeDelayMs = 5_000 // p. ej. connect-network escaneando
        assertNull(env.controller.quickSwitch(NetworkAction.ConnectWifi(secondary)))
        runCurrent()

        assertNull(env.controller.startScenario(Scenario.TEMPORARY_LOSS))
        env.controller.stopScenario() // el job nuevo aún espera a que termine el anterior
        advanceTimeBy(6_000)
        runCurrent()

        assertNull(env.controller.activeRun.value)
        assertFalse(env.controller.stopping.value)
        assertNull("debe aceptar nuevas acciones", env.controller.quickSwitch(NetworkAction.RestoreAll(primary)))
        assertTrue(TestEventLogger.entries.value.any { it.message == "Prueba detenida antes de iniciar" })
    }

    @Test
    fun `detener durante el corte restaura al instante sin esperar confirmacion`() = runTest {
        val env = env(this)
        env.controller.startScenario(Scenario.TEMPORARY_LOSS)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(Transport.NONE, env.snapshot.value.status.activeTransport)

        val stopAt = currentTime
        env.controller.stopScenario()
        assertTrue(env.controller.stopping.value)
        runCurrent()

        assertNull(env.controller.activeRun.value)
        assertEquals(stopAt, currentTime)
        assertEquals(NetworkAction.RestoreAll(primary), env.network.executed.last().second)
        assertEquals("Prueba detenida", TestEventLogger.entries.value.last { it.eventType == EventType.TEST_END }.message)
    }

    @Test
    fun `detener justo despues del corte restaura aunque Android aun no reporte la caida`() = runTest {
        val env = env(this)
        env.controller.startScenario(Scenario.TEMPORARY_LOSS)
        advanceTimeBy(500) // corte ejecutado; su efecto llega a los 1 000 ms
        runCurrent()
        assertEquals(Transport.WIFI, env.snapshot.value.status.activeTransport)

        env.controller.stopScenario()
        runCurrent()

        assertEquals(NetworkAction.RestoreAll(primary), env.network.executed.last().second)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(Transport.WIFI, env.snapshot.value.status.activeTransport)
    }

    @Test
    fun `detener mientras se mide la recuperacion responde sin esperar 45 s`() = runTest {
        val env = env(this)
        env.network.ignore = setOf(NetworkAction.RestoreAll(primary)) // la red nunca vuelve
        env.controller.startScenario(Scenario.TEMPORARY_LOSS)
        advanceTimeBy(20_000) // corte + 15 s; ahora espera confirmación de la restauración
        runCurrent()
        assertNotNull(env.controller.activeRun.value)

        env.controller.stopScenario()
        runCurrent()

        assertNull(env.controller.activeRun.value)
    }

    @Test
    fun `perdida temporal completa vuelve a la principal y mide la recuperacion`() = runTest {
        val env = env(this)
        env.controller.startScenario(Scenario.TEMPORARY_LOSS)
        advanceTimeBy(20_000)
        runCurrent()

        assertNull(env.controller.activeRun.value)
        assertEquals("Principal", env.network.ssid)
        val messages = TestEventLogger.entries.value.map { it.message }
        assertTrue(messages.any { it.startsWith("Restaurar a Principal: confirmado en") })
        assertTrue("Prueba completada" in messages)
    }

    @Test
    fun `intermitencia respeta la cadencia configurada y repite hasta detener`() = runTest {
        val env = env(this, ScenarioConfig(intermittenceOffSeconds = 4, intermittenceOnSeconds = 6))
        env.controller.startScenario(Scenario.INTERMITTENCE)
        runCurrent()
        assertEquals(Countdown(6_000, "Sin red"), env.controller.countdown.value)

        advanceTimeBy(36_001)
        runCurrent()

        val schedule = env.network.executed.map { it.first to it.second::class.simpleName }
        assertEquals(
            listOf(
                6_000L to "NoNetwork", 10_000L to "RestoreAll",
                16_000L to "NoNetwork", 20_000L to "RestoreAll",
                26_000L to "NoNetwork", 30_000L to "RestoreAll",
                36_000L to "NoNetwork"
            ),
            schedule
        )
        assertEquals(Countdown(40_000, "Principal"), env.controller.countdown.value)

        env.controller.stopScenario()
        runCurrent()
        assertNull(env.controller.activeRun.value)
        assertNull(env.controller.countdown.value)
        assertEquals(NetworkAction.RestoreAll(primary), env.network.executed.last().second)
        assertTrue(TestEventLogger.entries.value.any {
            it.message.startsWith("Intermitencia, reconexión (3/3 confirmados)")
        })
    }

    @Test
    fun `intermitencia sin red principal reactiva el Wi-Fi y lo deja registrado`() = runTest {
        val env = env(this, ScenarioConfig(intermittenceOffSeconds = 4, intermittenceOnSeconds = 6), listOf(secondary))
        assertNull(env.controller.startScenario(Scenario.INTERMITTENCE))
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(NetworkAction.RestoreAll(null), env.network.executed.last().second)
        assertTrue(TestEventLogger.entries.value.any { it.message.startsWith("Sin red principal") })
        env.controller.stopScenario()
        runCurrent()
        assertNull(env.controller.activeRun.value)
    }

    @Test
    fun `rotacion muestra la cuenta regresiva hacia la siguiente red`() = runTest {
        val env = env(this, ScenarioConfig(rotationIntervalSeconds = 20))
        env.controller.startScenario(Scenario.NETWORK_ROTATION)
        advanceTimeBy(1_500) // "Principal" ya activa (0 ms); espera 20 s para ir a "Secundaria"
        runCurrent()
        assertEquals(Countdown(20_000, "Secundaria"), env.controller.countdown.value)

        advanceTimeBy(20_000) // cambia a Secundaria (confirmada en 1 s)
        runCurrent()
        assertEquals("Secundaria", env.network.ssid)
        assertEquals(Countdown(41_000, "Principal"), env.controller.countdown.value)

        env.controller.stopScenario()
        runCurrent()
        assertNull(env.controller.activeRun.value)
        assertEquals(NetworkAction.RestoreAll(primary), env.network.executed.last().second)
    }

    @Test
    fun `corte durante operacion se mantiene hasta detener y advierte resultado desconocido`() = runTest {
        val env = env(this)
        env.controller.startScenario(Scenario.DROP_DURING_OPERATION)
        advanceTimeBy(120_000)
        runCurrent()
        assertNotNull(env.controller.activeRun.value)
        assertEquals(Transport.NONE, env.snapshot.value.status.activeTransport)

        env.controller.stopScenario()
        runCurrent()
        assertNull(env.controller.activeRun.value)
        assertTrue(TestEventLogger.entries.value.any { it.message.contains("DESCONOCIDO") })
    }

    @Test
    fun `restaurar sin principal solo enciende el Wi-Fi`() = runTest {
        val env = env(this, profiles = listOf(secondary))
        assertEquals(NetworkAction.RestoreAll(null), env.controller.restoreAction())
    }

    @Test
    fun `un cambio rapido nuevo reemplaza al que espera confirmacion`() = runTest {
        val env = env(this)
        env.network.ignore = setOf(NetworkAction.ConnectWifi(secondary))
        env.controller.quickSwitch(NetworkAction.ConnectWifi(secondary))
        advanceTimeBy(2_000)
        env.controller.quickSwitch(NetworkAction.NoNetwork)
        advanceTimeBy(2_000)
        runCurrent()

        assertNull(env.controller.pendingAction.value)
        assertEquals(Transport.NONE, env.snapshot.value.status.activeTransport)
        assertTrue(TestEventLogger.entries.value.any {
            it.message == "Wi-Fi: Secundaria: interrumpido antes de confirmar"
        })
    }

    private class Env(
        val controller: ScenarioController,
        val network: FakeNetwork,
        val snapshot: MutableStateFlow<ConnectivitySnapshot>
    )

    private fun env(
        test: TestScope,
        config: ScenarioConfig = ScenarioConfig(),
        profiles: List<WifiProfile> = listOf(primary, secondary)
    ): Env {
        val snapshot = MutableStateFlow(FakeNetwork.wifi("Principal", 0))
        val network = FakeNetwork(test.backgroundScope, snapshot, profiles) { test.testScheduler.currentTime }
        val controller = ScenarioController(
            scope = test.backgroundScope,
            network = network,
            snapshot = snapshot,
            currentSsid = { network.ssid },
            profiles = { profiles },
            config = { config },
            clock = { test.testScheduler.currentTime }
        )
        return Env(controller, network, snapshot)
    }

    /** Red simulada: ejecuta acciones con retardo y aplica el nuevo estado 1 s después. */
    private class FakeNetwork(
        private val scope: CoroutineScope,
        private val snapshot: MutableStateFlow<ConnectivitySnapshot>,
        private val profiles: List<WifiProfile>,
        private val now: () -> Long
    ) : NetworkControl {
        override val isReady = true
        override val readinessLabel = "listo"
        var executeDelayMs = 0L
        var applyDelayMs = 1_000L
        var ignore: Set<NetworkAction> = emptySet()
        var ssid: String? = "Principal"
        val executed = mutableListOf<Pair<Long, NetworkAction>>()
        private var changes = 1L

        override suspend fun perform(action: NetworkAction): SwitchOutcome {
            executed += now() to action
            delay(executeDelayMs)
            if (action !in ignore) {
                scope.launch {
                    delay(applyDelayMs)
                    apply(action)
                }
            }
            return SwitchOutcome.Executed
        }

        private fun apply(action: NetworkAction) {
            val target = when (action) {
                is NetworkAction.ConnectWifi -> action.profile.ssid
                is NetworkAction.RestoreAll -> (action.primary ?: profiles.first()).ssid
                else -> null
            }
            ssid = target
            snapshot.value = when (action) {
                NetworkAction.NoNetwork -> none(changes++)
                NetworkAction.MobileOnly -> none(changes++)
                else -> wifi(target!!, changes++)
            }
        }

        override suspend fun isExpectedState(action: NetworkAction, snapshot: ConnectivitySnapshot): Boolean {
            val onWifi = snapshot.status.activeTransport == Transport.WIFI && snapshot.internetOk
            return when (action) {
                is NetworkAction.ConnectWifi -> onWifi && ssid == action.profile.ssid
                is NetworkAction.RestoreAll -> onWifi && (action.primary == null || ssid == action.primary.ssid)
                NetworkAction.NoNetwork -> snapshot.status.activeTransport == Transport.NONE
                NetworkAction.MobileOnly -> snapshot.status.activeTransport == Transport.CELLULAR
            }
        }

        override suspend fun currentWifiSsid(): String? = ssid

        companion object {
            fun wifi(ssid: String, change: Long) = ConnectivitySnapshot(
                NetworkStatus(
                    wifiConnected = true,
                    activeTransport = Transport.WIFI,
                    lastChangeMillis = change,
                    lastChangeDescription = "wifi $ssid"
                ),
                ProbeResult(ok = true, httpCode = 204, latencyMs = 50, error = null, transport = Transport.WIFI, timestampMillis = change)
            )

            fun none(change: Long) = ConnectivitySnapshot(
                NetworkStatus(activeTransport = Transport.NONE, lastChangeMillis = change, lastChangeDescription = "sin red"),
                ProbeResult(ok = false, httpCode = null, latencyMs = null, error = "sin red", transport = Transport.NONE, timestampMillis = change)
            )
        }
    }
}
