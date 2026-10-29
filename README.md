# POS Network Tester

App Android (Kotlin) para probar cómo responde una app de pagos ante cambios y caídas de
red en un terminal POS. Muestra un panel flotante sobre cualquier app con la red actual e
Internet real, permite cambiar de red con un toque (Wi-Fi ↔ Wi-Fi ↔ móvil ↔ sin red) y
ejecuta pruebas repetibles (pérdida temporal, intermitencia, rotación…) con un historial
exportable a CSV.

- **Guía para usuarios (instalación y uso):** [docs/INSTALACION.md](docs/INSTALACION.md)
- Solo para entornos de prueba o certificación: cambia la red real del POS.

## Instalación en un POS (instrucciones para agentes)

Ejecuta los pasos en orden y verifica cada uno antes de seguir. `ADB` es
`$HOME/Library/Android/sdk/platform-tools/adb` en macOS o `adb` si está en el PATH.

1. **Dispositivo conectado.** `adb devices -l` debe listar el POS como `device`.
2. **Instalar Shizuku** (necesario para cambiar la red; ver *Por qué Shizuku*). Si
   `adb shell pm path moe.shizuku.privileged.api` no devuelve nada:
   - Descarga el APK de la última release oficial: `https://api.github.com/repos/RikkaApps/Shizuku/releases/latest`
     → asset `shizuku-*-release.apk`. Guárdalo en un directorio vacío propio.
   - Verifica antes de instalar: el paquete es `moe.shizuku.privileged.api`
     (`aapt2 dump badging`), el firmante es `CN=Rikka` (`apksigner verify --print-certs`)
     y `native-code` incluye la ABI del POS (`adb shell getprop ro.product.cpu.abi`).
   - `adb install <apk>`
3. **Arrancar Shizuku** (también tras cada reinicio del POS): `./scripts/start_shizuku.sh`.
   Debe terminar con `shizuku_starter exit with 0`, y
   `adb shell "ps -A -o NAME | grep shizuku_server"` debe mostrar el proceso.
4. **Compilar e instalar la app:**

   ```sh
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

5. **Permisos.** El usuario lo hace desde la app, o para pruebas automatizadas:

   ```sh
   adb shell appops set com.example.pos_network_tester SYSTEM_ALERT_WINDOW allow
   adb shell pm grant com.example.pos_network_tester android.permission.READ_PHONE_STATE
   ```

   Abre la app (`adb shell am start -n com.example.pos_network_tester/.MainActivity`):
   la sección *Control de red* debe decir **"Shizuku listo"**. Si dice "Falta autorizar",
   el usuario pulsa *Autorizar en Shizuku* → *Permitir siempre*.
6. **Perfiles Wi-Fi.** Los configura el usuario en la app (SSID exacto, seguridad, clave,
   ★ red principal). No pidas, registres ni imprimas claves Wi-Fi.

Notas para automatizar en el dispositivo:
- `uiautomator dump` **no ve** el panel flotante (es una ventana `TYPE_APPLICATION_OVERLAY`):
  usa capturas (`adb exec-out screencap -p`) para inspeccionarlo.
- `FloatingPanelService` no está exportado: `am start-foreground-service` desde ADB falla
  a propósito. Inícialo con el botón de la app.
- Los POS XGD muestran periódicamente un diálogo del sistema *"Modo de depuración…"* que
  tapa la pantalla: hay que pulsar *Aceptar*.

## Por qué Shizuku

Desde `targetSdk` 29, una app normal no puede cambiar de red Wi-Fi ni encender o apagar
el Wi-Fi o los datos (`WifiManager.enableNetwork`/`setWifiEnabled` no hacen nada;
`MODIFY_PHONE_STATE` es de firma). Que el POS esté conectado por USB tampoco da esos
permisos a la app. Shizuku ejecuta un servicio con UID `shell` (el de `adb shell`), y la
app lo usa solo con una lista cerrada de operaciones (`privileged/IPrivilegedShell.aidl`):
`cmd wifi connect-network`, `cmd wifi set-wifi-enabled`, `svc data` y `cmd wifi status`.
Los argumentos se pasan como argv, sin intérprete de shell. Sin Shizuku la app solo
observa y muestra el comando ADB equivalente; nunca simula un cambio.

## Arquitectura

```
app/src/main/java/com/example/pos_network_tester/
├── MainActivity.kt              Configuración (Compose): permisos, Shizuku, perfiles, tiempos, sonda, historial
├── PosNetworkTesterApp.kt       Inicializa stores y Shizuku
├── overlay/
│   ├── FloatingPanelService.kt  Servicio foreground (specialUse) dueño del overlay; une los flujos con la vista
│   └── NetworkPanelView.kt      UI con Views nativas: globo, barra de cambio rápido y bandeja de pruebas
├── network/
│   ├── NetworkStatusReader.kt   NetworkCallback + registerDefaultNetworkCallback → NetworkStatus
│   ├── InternetProbe.kt         Sonda HTTP atada a la red activa → "Internet real"
│   ├── NetworkSwitcher.kt       NetworkAction → comandos vía Shizuku; estado esperado de cada acción
│   ├── WifiProfileStore.kt      Perfiles Wi-Fi (+ red principal), SharedPreferences privadas sin backup
│   └── ProbeSettings.kt         URL de la sonda
├── privileged/
│   ├── IPrivilegedShell.aidl    (en src/main/aidl) operaciones permitidas
│   ├── PrivilegedShellService.kt  Corre con UID shell dentro de Shizuku
│   └── ShizukuExecutor.kt       Estado de Shizuku, permiso, bind del servicio
├── simulation/
│   ├── Scenario.kt              Catálogo de pruebas + ScenarioConfig
│   ├── ScenarioSettings.kt      Tiempos configurables persistidos
│   └── ScenarioController.kt    Cambios rápidos y pruebas: confirmación, cuenta regresiva, detener
└── logging/TestEventLogger.kt   Historial en memoria + exportación CSV (sin datos sensibles)
```

## Comandos de desarrollo

```sh
./gradlew assembleDebug          # APK debug
./gradlew :app:testDebugUnitTest # pruebas del ScenarioController (tiempo virtual, red simulada)
./gradlew assembleRelease        # R8 activado; requiere configurar la firma para instalarlo
```

## Decisiones y comportamientos que no se deducen del código

- **Internet real ≠ "validado".** En el XGD N96, `captive_portal_mode=0` desactiva la
  validación de Android: toda red aparece como `VALIDATED`, incluidos los portales cautivos.
  Por eso un cambio solo se confirma con una sonda HTTP posterior al cambio.
- **Escaneo previo.** Con resultados de escaneo de más de ~30 s, `connect-network` falla
  en ~1 s y Android vuelve a la red anterior. `PrivilegedShellService` escanea antes si hace falta.
- **Red predeterminada.** `ConnectivityManager.activeNetwork` puede devolver una red ya
  perdida durante segundos tras `onLost`. El estado se toma de `registerDefaultNetworkCallback`.
- **Detener siempre responde.** El estado de la prueba se limpia en `invokeOnCompletion`
  (incluso si se cancela antes de arrancar). Al detener, se restaura sin esperar
  confirmación. Una acción tras otra aún sin confirmar nunca se salta por "ya estaba en ese
  estado". Todo esto lo cubre `ScenarioControllerTest`.
- **`usesCleartextTraffic` en vez de `network_security_config`.** Las reglas por dominio
  hacen fallar el arranque del proceso shell de Shizuku (*Found multiple conflicting
  per-domain rules*).
- **Honestidad de las pruebas.** No se simula ningún cambio que no ocurre, un timeout no
  se presenta como fallo de la transacción, no hay reintentos automáticos de pagos, y
  latencia y timeout quedan marcados como "requiere integración con la app de pagos".
