package com.example.pos_network_tester.simulation

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ScenarioSettings {

    private const val PREFS_NAME = "scenario_settings"
    private const val KEY_OFF = "intermittence_off_s"
    private const val KEY_ON = "intermittence_on_s"
    private const val KEY_ROTATION = "rotation_interval_s"

    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow(ScenarioConfig())
    val config: StateFlow<ScenarioConfig> = _config.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = ScenarioConfig()
        _config.value = ScenarioConfig(
            intermittenceOffSeconds = prefs.getInt(KEY_OFF, defaults.intermittenceOffSeconds),
            intermittenceOnSeconds = prefs.getInt(KEY_ON, defaults.intermittenceOnSeconds),
            rotationIntervalSeconds = prefs.getInt(KEY_ROTATION, defaults.rotationIntervalSeconds)
        )
    }

    /** Devuelve un error legible o null si se guardó. */
    fun save(offSeconds: Int?, onSeconds: Int?, rotationSeconds: Int?): String? {
        val range = ScenarioConfig.MIN_SECONDS..ScenarioConfig.MAX_SECONDS
        if (listOf(offSeconds, onSeconds, rotationSeconds).any { it == null || it !in range }) {
            return "Los segundos deben estar entre ${range.first} y ${range.last}"
        }
        val config = ScenarioConfig(offSeconds!!, onSeconds!!, rotationSeconds!!)
        prefs.edit()
            .putInt(KEY_OFF, config.intermittenceOffSeconds)
            .putInt(KEY_ON, config.intermittenceOnSeconds)
            .putInt(KEY_ROTATION, config.rotationIntervalSeconds)
            .apply()
        _config.value = config
        return null
    }
}
