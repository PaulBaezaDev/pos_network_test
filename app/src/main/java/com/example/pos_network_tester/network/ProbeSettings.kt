package com.example.pos_network_tester.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** URL que usa la sonda de Internet real; idealmente el endpoint de salud del sandbox de pagos. */
object ProbeSettings {

    private const val PREFS_NAME = "probe_settings"
    private const val KEY_URL = "probe_url"

    private lateinit var prefs: SharedPreferences
    private val _url = MutableStateFlow(InternetProbe.DEFAULT_PROBE_URL)
    val url: StateFlow<String> = _url.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _url.value = prefs.getString(KEY_URL, null) ?: InternetProbe.DEFAULT_PROBE_URL
    }

    /** Devuelve un error legible o null si se guardó. */
    fun setUrl(raw: String): String? {
        val value = raw.trim().ifEmpty { InternetProbe.DEFAULT_PROBE_URL }
        if (value != InternetProbe.DEFAULT_PROBE_URL && !value.startsWith("https://")) {
            return "Usa una URL https:// (HTTP sin cifrar solo se permite para la sonda por defecto)"
        }
        prefs.edit().putString(KEY_URL, value).apply()
        _url.value = value
        return null
    }
}
