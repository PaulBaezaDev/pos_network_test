package com.example.pos_network_tester

import android.app.Application
import com.example.pos_network_tester.network.ProbeSettings
import com.example.pos_network_tester.network.WifiProfileStore
import com.example.pos_network_tester.privileged.ShizukuExecutor
import com.example.pos_network_tester.simulation.ScenarioSettings

class PosNetworkTesterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WifiProfileStore.init(this)
        ProbeSettings.init(this)
        ScenarioSettings.init(this)
        ShizukuExecutor.init(this)
    }
}
