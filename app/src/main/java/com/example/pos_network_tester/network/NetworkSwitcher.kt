package com.example.pos_network_tester.network

import com.example.pos_network_tester.privileged.CommandResult
import com.example.pos_network_tester.privileged.ExecutorState
import com.example.pos_network_tester.privileged.ShizukuExecutor

sealed class NetworkAction(val label: String, val shortLabel: String) {
    data class ConnectWifi(val profile: WifiProfile) :
        NetworkAction("Wi-Fi: ${profile.ssid}", profile.ssid)

    data object MobileOnly : NetworkAction("Solo móvil", "Móvil")
    data object NoNetwork : NetworkAction("Sin red", "Sin red")

    /** Activa datos y vuelve a la red principal; sin principal, solo enciende el Wi-Fi. */
    data class RestoreAll(val primary: WifiProfile?) : NetworkAction(
        if (primary != null) "Restaurar a ${primary.ssid}" else "Restaurar",
        primary?.ssid ?: "Restaurar"
    )
}

sealed class SwitchOutcome {
    data object Executed : SwitchOutcome()
    data class Failed(val reason: String) : SwitchOutcome()
    data class RequiresAdb(val reason: String, val adbCommands: List<String>) : SwitchOutcome()
}

/** Lo que el controlador de escenarios necesita de la red; permite probarlo sin dispositivo. */
interface NetworkControl {
    val isReady: Boolean
    val readinessLabel: String
    suspend fun perform(action: NetworkAction): SwitchOutcome
    suspend fun isExpectedState(action: NetworkAction, snapshot: ConnectivitySnapshot): Boolean
    suspend fun currentWifiSsid(): String?
}

/**
 * Ejecuta cambios reales de red a través de Shizuku. Si Shizuku no está
 * listo, no simula nada: devuelve los comandos ADB equivalentes.
 */
class NetworkSwitcher(private val executor: ShizukuExecutor = ShizukuExecutor) : NetworkControl {

    override val isReady: Boolean get() = executor.state.value == ExecutorState.READY
    override val readinessLabel: String get() = executor.state.value.label

    override suspend fun perform(action: NetworkAction): SwitchOutcome {
        if (!isReady) {
            return SwitchOutcome.RequiresAdb(
                reason = "$readinessLabel. Ejecuta desde el equipo de desarrollo:",
                adbCommands = adbEquivalent(action)
            )
        }
        val steps: List<suspend () -> CommandResult> = when (action) {
            is NetworkAction.ConnectWifi -> listOf { connect(action.profile) }
            // Datos primero y Wi-Fi después, para que el traspaso sea lo más corto posible.
            NetworkAction.MobileOnly -> listOf(
                { executor.setMobileDataEnabled(true) },
                { executor.setWifiEnabled(false) }
            )
            NetworkAction.NoNetwork -> listOf(
                { executor.setWifiEnabled(false) },
                { executor.setMobileDataEnabled(false) }
            )
            is NetworkAction.RestoreAll -> listOf(
                { executor.setMobileDataEnabled(true) },
                {
                    if (action.primary != null) connect(action.primary)
                    else executor.setWifiEnabled(true)
                }
            )
        }
        for (step in steps) {
            val result = step()
            if (!result.success) return SwitchOutcome.Failed(result.output.ifBlank { "Error sin detalle" })
        }
        return SwitchOutcome.Executed
    }

    /**
     * Estado que confirma que la acción tuvo efecto: no basta con que el comando
     * se ejecute ni con VALIDATED de Android (desactivado en este POS); se exige
     * la sonda HTTP posterior al cambio.
     */
    override suspend fun isExpectedState(action: NetworkAction, snapshot: ConnectivitySnapshot): Boolean {
        val status = snapshot.status
        return when (action) {
            is NetworkAction.ConnectWifi -> isOnWifi(snapshot, action.profile.ssid)
            NetworkAction.MobileOnly -> status.activeTransport == Transport.CELLULAR && snapshot.internetOk
            NetworkAction.NoNetwork -> status.activeTransport == Transport.NONE
            is NetworkAction.RestoreAll -> isOnWifi(snapshot, action.primary?.ssid)
        }
    }

    override suspend fun currentWifiSsid(): String? = executor.currentWifiSsid()

    private suspend fun isOnWifi(snapshot: ConnectivitySnapshot, requiredSsid: String?): Boolean =
        snapshot.status.activeTransport == Transport.WIFI &&
            snapshot.internetOk &&
            (requiredSsid == null || currentWifiSsid() == requiredSsid)

    private suspend fun connect(profile: WifiProfile): CommandResult =
        executor.connectWifi(profile.ssid, profile.security.cmdValue, profile.passphrase)

    private fun adbEquivalent(action: NetworkAction): List<String> = when (action) {
        is NetworkAction.ConnectWifi -> listOf(adbConnect(action.profile))
        NetworkAction.MobileOnly -> listOf(
            "adb shell svc data enable",
            "adb shell cmd wifi set-wifi-enabled disabled"
        )
        NetworkAction.NoNetwork -> listOf(
            "adb shell cmd wifi set-wifi-enabled disabled",
            "adb shell svc data disable"
        )
        is NetworkAction.RestoreAll -> listOf(
            "adb shell svc data enable",
            action.primary?.let(::adbConnect) ?: "adb shell cmd wifi set-wifi-enabled enabled"
        )
    }

    private fun adbConnect(profile: WifiProfile): String {
        val key = if (profile.security.needsPassphrase) " <clave>" else ""
        return "adb shell cmd wifi connect-network \"${profile.ssid}\" ${profile.security.cmdValue}$key"
    }
}
