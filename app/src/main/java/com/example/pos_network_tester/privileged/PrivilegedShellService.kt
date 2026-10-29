package com.example.pos_network_tester.privileged

import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Se ejecuta dentro del proceso que lanza Shizuku con UID shell (2000), el
 * mismo nivel de privilegio que `adb shell`. Los argumentos se pasan como
 * argv (sin intérprete de shell), así que un SSID o una clave con caracteres
 * especiales no puede inyectar comandos.
 */
class PrivilegedShellService : IPrivilegedShell.Stub() {

    private val allowedSecurity = setOf("open", "owe", "wpa2", "wpa3")

    // "  6e:3a:0e:2d:f1:1e  5280  -55(0:-55)  4,157  Visita-KLAP  [ESS]" — edad en ms; el SSID puede tener espacios.
    private val scanLine =
        Regex("""^\s*[0-9a-fA-F:]{17}\s+\d+\s+\S+\s+([\d.,]+)\s+(.+?)\s+(\[.*)?$""")

    private companion object {
        const val MAX_SCAN_AGE_MS = 30_000L
        const val SCAN_POLLS = 12
        const val SCAN_POLL_INTERVAL_MS = 500L
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun connectWifi(ssid: String, security: String, passphrase: String?): String {
        require(ssid.isNotBlank()) { "SSID vacío" }
        require(security in allowedSecurity) { "Seguridad no soportada: $security" }
        run("cmd", "wifi", "set-wifi-enabled", "enabled")
        if (!ensureRecentlyScanned(ssid)) {
            return "2|\"$ssid\" no aparece en un escaneo reciente (fuera de alcance u oculta)"
        }
        val argv = mutableListOf("cmd", "wifi", "connect-network", ssid, security)
        if (security != "open" && security != "owe") {
            require(!passphrase.isNullOrEmpty()) { "La red $ssid requiere clave" }
            argv += passphrase
        }
        return run(*argv.toTypedArray()).let { result ->
            if (passphrase.isNullOrEmpty()) result else result.replace(passphrase, "***")
        }
    }

    override fun setWifiEnabled(enabled: Boolean): String =
        run("cmd", "wifi", "set-wifi-enabled", if (enabled) "enabled" else "disabled")

    override fun setMobileDataEnabled(enabled: Boolean): String =
        run("svc", "data", if (enabled) "enable" else "disable")

    override fun currentWifiSsid(): String {
        val result = run("cmd", "wifi", "status")
        val match = Regex("Wifi is connected to \"(.*)\"").find(result)
        return match?.groupValues?.get(1).orEmpty()
    }

    /**
     * Verificado en el N96: con resultados de escaneo de ~3 min, connect-network
     * falla en ~1 s y Android vuelve a la red anterior; con un escaneo reciente
     * el cambio tarda ~1 s.
     */
    private fun ensureRecentlyScanned(ssid: String): Boolean {
        if (isRecentlyScanned(ssid)) return true
        run("cmd", "wifi", "start-scan")
        repeat(SCAN_POLLS) {
            Thread.sleep(SCAN_POLL_INTERVAL_MS)
            if (isRecentlyScanned(ssid)) return true
        }
        return false
    }

    private fun isRecentlyScanned(ssid: String): Boolean {
        val listing = run("cmd", "wifi", "list-scan-results").substringAfter('|')
        return listing.lineSequence().any { line ->
            val match = scanLine.find(line) ?: return@any false
            val ageMs = match.groupValues[1].replace(",", "").replace(".", "").toLongOrNull()
            match.groupValues[2] == ssid && ageMs != null && ageMs < MAX_SCAN_AGE_MS
        }
    }

    private fun run(vararg argv: String): String {
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        // Salidas de cmd wifi/svc son pequeñas: esperar antes de leer no llena el pipe.
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroy()
            return "-1|timeout ejecutando ${argv.take(3).joinToString(" ")}"
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        return "${process.exitValue()}|$output"
    }
}
