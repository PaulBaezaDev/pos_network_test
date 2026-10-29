package com.example.pos_network_tester.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class WifiSecurity(val cmdValue: String, val label: String) {
    OPEN("open", "Abierta"),
    WPA2("wpa2", "WPA2"),
    WPA3("wpa3", "WPA3");

    val needsPassphrase: Boolean get() = this != OPEN
}

data class WifiProfile(
    val id: String = UUID.randomUUID().toString(),
    val ssid: String,
    val security: WifiSecurity,
    val passphrase: String,
    /** Red con Internet estable elegida por el usuario: destino de "Restaurar". Solo puede haber una. */
    val isPrimary: Boolean = false
) {
    override fun toString(): String =
        "WifiProfile(ssid=$ssid, security=${security.cmdValue}, primary=$isPrimary)"
}

/**
 * Perfiles de red para el cambio rápido. Se guardan en SharedPreferences
 * privadas de la app, excluidas de backup y de transferencia entre
 * dispositivos (ver res/xml). La clave nunca se muestra ni se registra.
 */
object WifiProfileStore {

    private const val PREFS_NAME = "wifi_profiles"
    private const val KEY_PROFILES = "profiles"

    private lateinit var prefs: SharedPreferences
    private val _profiles = MutableStateFlow<List<WifiProfile>>(emptyList())
    val profiles: StateFlow<List<WifiProfile>> = _profiles.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _profiles.value = load()
    }

    fun add(ssid: String, security: WifiSecurity, passphrase: String) {
        val profile = WifiProfile(
            ssid = ssid.trim(),
            security = security,
            passphrase = if (security.needsPassphrase) passphrase else ""
        )
        save(_profiles.value + profile)
    }

    fun remove(id: String) {
        save(_profiles.value.filterNot { it.id == id })
    }

    /** Marca el perfil como principal (desmarcando el resto); con el mismo id ya principal, lo desmarca. */
    fun togglePrimary(id: String) {
        val alreadyPrimary = _profiles.value.any { it.id == id && it.isPrimary }
        save(_profiles.value.map { it.copy(isPrimary = !alreadyPrimary && it.id == id) })
    }

    fun primary(): WifiProfile? = _profiles.value.firstOrNull { it.isPrimary }

    private fun save(list: List<WifiProfile>) {
        val array = JSONArray()
        list.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("ssid", profile.ssid)
                    .put("security", profile.security.name)
                    .put("passphrase", profile.passphrase)
                    .put("primary", profile.isPrimary)
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
        _profiles.value = list
    }

    private fun load(): List<WifiProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            WifiProfile(
                id = item.getString("id"),
                ssid = item.getString("ssid"),
                security = WifiSecurity.valueOf(item.getString("security")),
                passphrase = item.optString("passphrase"),
                isPrimary = item.optBoolean("primary", false)
            )
        }
    }
}
