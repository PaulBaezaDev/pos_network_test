package com.example.pos_network_tester.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

data class ProbeResult(
    val ok: Boolean,
    val httpCode: Int?,
    val latencyMs: Long?,
    val error: String?,
    val transport: Transport,
    val timestampMillis: Long = System.currentTimeMillis()
) {
    fun label(): String = if (ok) "OK (${latencyMs} ms)" else "Falla: $error"
}

/** Estado de red + resultado de la sonda HTTP, evaluados juntos. */
data class ConnectivitySnapshot(val status: NetworkStatus, val probe: ProbeResult?) {
    /** Solo cuenta una sonda posterior al último cambio de red: una OK de la red anterior no vale. */
    private val freshProbe: ProbeResult?
        get() = probe?.takeIf { it.timestampMillis >= status.lastChangeMillis }

    val hasFreshProbe: Boolean get() = freshProbe != null

    val internetOk: Boolean get() = freshProbe?.ok == true

    fun internetLabel(): String = when {
        status.activeTransport == Transport.NONE -> "Sin red"
        freshProbe == null -> "Comprobando…"
        else -> freshProbe!!.label()
    }
}

/**
 * Comprueba la salida real a Internet con una petición HTTP atada a la red
 * activa. Necesario porque en este POS `captive_portal_mode=0` desactiva la
 * validación de Android y toda red aparece como "validada", incluidos los
 * portales cautivos.
 */
class InternetProbe(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _result = MutableStateFlow<ProbeResult?>(null)
    val resultFlow: StateFlow<ProbeResult?> = _result.asStateFlow()

    suspend fun probeNow(probeUrl: String): ProbeResult = withContext(Dispatchers.IO) {
        val result = runProbe(probeUrl)
        _result.value = result
        result
    }

    private fun runProbe(probeUrl: String): ProbeResult {
        val network = connectivityManager.activeNetwork
            ?: return ProbeResult(false, null, null, "sin red activa", Transport.NONE)
        val caps = connectivityManager.getNetworkCapabilities(network)
        val transport = when {
            caps == null -> Transport.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.NONE
        }
        val startedAt = SystemClock.elapsedRealtime()
        var connection: HttpURLConnection? = null
        return try {
            connection = (network.openConnection(URL(probeUrl)) as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Connection", "close")
            }
            val code = connection.responseCode
            val latency = SystemClock.elapsedRealtime() - startedAt
            val expectsNoContent = probeUrl == DEFAULT_PROBE_URL
            val ok = if (expectsNoContent) code == 204 else code in 200..299
            val error = when {
                ok -> null
                code in 300..399 -> "HTTP $code (redirección: ¿portal cautivo?)"
                else -> "HTTP $code"
            }
            ProbeResult(ok, code, latency, error, transport)
        } catch (_: SocketTimeoutException) {
            ProbeResult(false, null, null, "timeout ${TIMEOUT_MS / 1000} s", transport)
        } catch (_: UnknownHostException) {
            ProbeResult(false, null, null, "DNS no resuelve", transport)
        } catch (e: IOException) {
            ProbeResult(false, null, null, e.javaClass.simpleName, transport)
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val DEFAULT_PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
        private const val TIMEOUT_MS = 3_000
    }
}
