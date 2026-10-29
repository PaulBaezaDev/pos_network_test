package com.example.pos_network_tester.privileged;

// Lista cerrada de operaciones: no expone ejecución de comandos arbitrarios.
// Los comandos devuelven "<exitCode>|<salida>"; currentWifiSsid devuelve el SSID o "".
interface IPrivilegedShell {
    void destroy() = 16777114;

    String connectWifi(String ssid, String security, String passphrase) = 1;
    String setWifiEnabled(boolean enabled) = 2;
    String setMobileDataEnabled(boolean enabled) = 3;
    String currentWifiSsid() = 4;
}
