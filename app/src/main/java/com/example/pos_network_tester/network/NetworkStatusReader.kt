package com.example.pos_network_tester.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class MobileGeneration(val label: String) {
    G2("2G"),
    G3("3G"),
    G4("4G/LTE"),
    G5("5G"),
    NONE("Sin SIM lista"),
    UNKNOWN("Desconocida")
}

enum class Transport(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Datos móviles"),
    ETHERNET("Ethernet"),
    NONE("Sin conexión")
}

data class NetworkStatus(
    val wifiConnected: Boolean = false,
    val cellularConnected: Boolean = false,
    val activeTransport: Transport = Transport.NONE,
    val isInternetValidated: Boolean = false,
    /** false si el POS tiene captive_portal_mode=0: Android marca toda red como validada sin comprobar. */
    val androidValidationEnabled: Boolean = true,
    val mobileGeneration: MobileGeneration = MobileGeneration.UNKNOWN,
    val simReady: Boolean = false,
    val mobileDataEnabled: Boolean? = null,
    val lastChangeMillis: Long = System.currentTimeMillis(),
    val lastChangeDescription: String = "Estado inicial"
) {
    fun wifiLabel(ssid: String?): String = when {
        !wifiConnected -> "Desconectado"
        !ssid.isNullOrEmpty() -> ssid
        else -> "Conectado"
    }

    fun mobileLabel(): String = when {
        !simReady -> "SIM no lista"
        cellularConnected -> "Conectado"
        mobileDataEnabled == false -> "Desactivados"
        else -> "Activados, sin red"
    }

    fun sameStateAs(other: NetworkStatus): Boolean =
        copy(lastChangeMillis = 0, lastChangeDescription = "") ==
            other.copy(lastChangeMillis = 0, lastChangeDescription = "")
}

/**
 * Observa la conectividad real del sistema mediante NetworkCallback. Sigue
 * todas las redes con capacidad de Internet (no solo la activa), distingue
 * "red presente" de "Internet validado por Android" y solo emite cuando el
 * estado cambia de verdad.
 */
class NetworkStatusReader(private val context: Context) {

    private companion object {
        const val CAPTIVE_PORTAL_MODE_SETTING = "captive_portal_mode"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val networks = ConcurrentHashMap<Network, NetworkCapabilities>()

    private val _status = MutableStateFlow(NetworkStatus())
    val statusFlow: StateFlow<NetworkStatus> = _status.asStateFlow()

    /**
     * Red predeterminada según registerDefaultNetworkCallback. No se usa
     * connectivityManager.activeNetwork porque al apagar el Wi-Fi puede seguir
     * devolviendo la red perdida varios segundos después de onLost.
     */
    @Volatile
    private var defaultNetwork: Network? = null

    @Volatile
    private var defaultCaps: NetworkCapabilities? = null

    private var registered = false

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
            defaultCaps = connectivityManager.getNetworkCapabilities(network)
            refreshNow("Red predeterminada: disponible")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            defaultNetwork = network
            defaultCaps = capabilities
            refreshNow("Capacidades de red actualizadas")
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) {
                defaultNetwork = null
                defaultCaps = null
            }
            refreshNow("Red predeterminada: perdida")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connectivityManager.getNetworkCapabilities(network)?.let { networks[network] = it }
            refreshNow("Red disponible")
        }

        override fun onLost(network: Network) {
            networks.remove(network)
            refreshNow("Red perdida")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            networks[network] = capabilities
            refreshNow("Capacidades de red actualizadas")
        }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback)
        registered = true
        refreshNow("Monitor iniciado")
    }

    fun stop() {
        if (!registered) return
        connectivityManager.unregisterNetworkCallback(networkCallback)
        connectivityManager.unregisterNetworkCallback(defaultNetworkCallback)
        networks.clear()
        defaultNetwork = null
        defaultCaps = null
        registered = false
    }

    @Synchronized
    fun refreshNow(reason: String) {
        val all = networks.values
        val activeCaps = defaultCaps

        val activeTransport = when {
            activeCaps == null -> Transport.NONE
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.NONE
        }
        val simReady = telephonyManager?.simState == TelephonyManager.SIM_STATE_READY

        val candidate = NetworkStatus(
            wifiConnected = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
            cellularConnected = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
            activeTransport = activeTransport,
            isInternetValidated =
                activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            androidValidationEnabled = readAndroidValidationEnabled(),
            mobileGeneration = if (simReady) readMobileGeneration() else MobileGeneration.NONE,
            simReady = simReady,
            mobileDataEnabled = readMobileDataEnabled(),
            lastChangeDescription = reason
        )
        if (!candidate.sameStateAs(_status.value)) {
            _status.value = candidate
        }
    }

    private fun hasReadPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun readAndroidValidationEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, CAPTIVE_PORTAL_MODE_SETTING, 1) != 0
    } catch (_: SecurityException) {
        true
    }

    private fun readMobileDataEnabled(): Boolean? {
        if (!hasReadPhoneStatePermission()) return null
        return try {
            telephonyManager?.isDataEnabled
        } catch (_: SecurityException) {
            null
        }
    }

    private fun readMobileGeneration(): MobileGeneration {
        if (!hasReadPhoneStatePermission()) return MobileGeneration.UNKNOWN
        val manager = telephonyManager ?: return MobileGeneration.UNKNOWN
        return try {
            mapNetworkType(manager.dataNetworkType)
        } catch (_: SecurityException) {
            MobileGeneration.UNKNOWN
        }
    }

    @Suppress("DEPRECATION")
    private fun mapNetworkType(networkType: Int): MobileGeneration = when (networkType) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,
        TelephonyManager.NETWORK_TYPE_GSM -> MobileGeneration.G2

        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> MobileGeneration.G3

        TelephonyManager.NETWORK_TYPE_LTE,
        TelephonyManager.NETWORK_TYPE_IWLAN -> MobileGeneration.G4

        TelephonyManager.NETWORK_TYPE_NR -> MobileGeneration.G5

        else -> MobileGeneration.UNKNOWN
    }
}
