# POS Network Tester: instalación y uso

Guía para instalar y configurar POS Network Tester en un terminal POS Android y usarla
durante las pruebas de la app de pagos.

> **Solo para entornos de prueba o certificación.** La app cambia la red real del POS
> (Wi-Fi y datos) para todas las apps. No la uses con pagos reales.

---

## 1. Qué hace

- Muestra un **panel flotante** sobre cualquier app (incluida la de pagos) con la red
  actual y si hay **Internet real**. Para eso hace una petición HTTP propia: no se fía
  del indicador de Android, que en estos POS está desactivado.
- **Cambio rápido** de red con un toque: tus perfiles Wi-Fi, "Móvil", "Sin red" y
  "Restaurar" (vuelve a la red principal).
- **Pruebas automáticas** de pérdida de conexión, intermitencia y rotación de redes,
  con cuenta regresiva visible.
- **Historial** de eventos con un identificador por prueba, exportable a CSV.

## 2. Por qué hace falta Shizuku

Android **no deja** que una app normal encienda o apague el Wi-Fi, cambie de red Wi-Fi
ni active o desactive los datos móviles. Esas funciones están reservadas al sistema.
Que el POS esté conectado por USB con ADB **no le da esos permisos a la app**.

[Shizuku](https://github.com/RikkaApps/Shizuku) es una app de código abierto que se
arranca una vez por ADB y ofrece a las apps que tú autorices el mismo nivel de permiso
que tiene `adb shell`. POS Network Tester la usa **solo** para estas operaciones:

| Operación | Comando que ejecuta |
|---|---|
| Conectar a un perfil Wi-Fi | `cmd wifi connect-network <ssid> <seguridad> <clave>` |
| Encender o apagar el Wi-Fi | `cmd wifi set-wifi-enabled enabled/disabled` |
| Activar o desactivar los datos móviles | `svc data enable/disable` |
| Leer el SSID actual | `cmd wifi status` |

No ejecuta ningún otro comando. Sin Shizuku la app sigue funcionando como **monitor**,
pero los botones de cambio no hacen nada: muestran el comando ADB equivalente.

## 3. Requisitos

- POS Android 8.0 o superior. Está probada en un **XGD N96 (Android 11)**.
- Un PC con **ADB**: [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools).
- **Depuración USB** activada en el POS: *Ajustes → Opciones de desarrollador*.
  Los POS XGD muestran de vez en cuando el aviso *"Modo de depuración: no usar para fines
  comerciales"*. Es normal: pulsa **Aceptar**.
- El APK de POS Network Tester (por ejemplo `pos-network-tester.apk`).

Comprueba la conexión:

```sh
adb devices
# Debe aparecer el POS como "device". Si dice "unauthorized", acepta el diálogo en el POS.
```

## 4. Instalación paso a paso

### 4.1 Instalar Shizuku

Los POS no suelen tener Play Store, así que se instala el APK por ADB:

1. En el PC, descarga el APK más reciente de
   <https://github.com/RikkaApps/Shizuku/releases> (archivo `shizuku-vX.Y.Z...-release.apk`).
2. Instálalo:

   ```sh
   adb install shizuku-v13.6.0.r1086.2650830c-release.apk
   ```

### 4.2 Arrancar Shizuku (también tras cada reinicio del POS)

Shizuku **se detiene cada vez que el POS se reinicia**, así que este paso hay que
repetirlo después de cada reinicio.

**macOS o Linux**, desde la carpeta del proyecto:

```sh
./scripts/start_shizuku.sh
```

**Windows o manual:**

```sh
adb shell pm path moe.shizuku.privileged.api
# package:/data/app/~~XXXX/moe.shizuku.privileged.api-YYYY/base.apk
```

Sustituye `base.apk` por `lib/arm/libshizuku.so` (en POS de 64 bits, `lib/arm64/libshizuku.so`)
y ejecútalo:

```sh
adb shell /data/app/~~XXXX/moe.shizuku.privileged.api-YYYY/lib/arm/libshizuku.so
```

Si todo va bien termina con `info: shizuku_starter exit with 0`. También puedes abrir la
app Shizuku → *Iniciar mediante ADB conectado*, que muestra el comando exacto.

### 4.3 Instalar POS Network Tester

```sh
adb install -r pos-network-tester.apk
```

### 4.4 Configurar la app (en el POS)

Abre **POS Network Tester** y, de arriba abajo:

1. **Permisos → Conceder superposición.** Se abre Ajustes: activa *Mostrar sobre otras apps*
   y vuelve atrás.
2. **Permisos de red y notificaciones.** Acepta los diálogos. Sirven para leer el tipo de
   red móvil (2G/3G/4G/5G) y mostrar la notificación del panel.
3. **Control de red (Shizuku)** tiene que decir **"Shizuku listo"**.
   - *"Shizuku no está en ejecución"*: repite el paso 4.2 y pulsa **Volver a comprobar**.
   - *"Falta autorizar esta app en Shizuku"*: pulsa **Autorizar en Shizuku** y luego
     **Permitir siempre**.
4. **Perfiles Wi-Fi para cambio rápido.** Añade cada red con su **SSID exacto**
   (distingue mayúsculas y espacios), su tipo de seguridad y su clave.
   - Marca con **★ la red principal**: la de Internet estable. "Restaurar" y todas las
     pruebas vuelven a ella. Sin red principal, "Restaurar" solo enciende el Wi-Fi y
     Android elige la red guardada.
   - Las claves se guardan solo en el POS: no se muestran, no se registran en el
     historial, no se exportan y no entran en copias de seguridad.
5. **Tiempos de las pruebas** (opcional): segundos de Intermitencia (sin red / con red) y
   el intervalo de Rotación. Pulsa **Guardar tiempos**.
6. **Sonda de Internet real** (opcional): por defecto consulta
   `http://connectivitycheck.gstatic.com/generate_204`. Si la cambias por el endpoint de
   salud del **sandbox de pagos** (https), el panel mide si ese servicio responde de verdad.
7. **Panel flotante → Iniciar panel flotante.**

## 5. Uso del panel flotante

| Estado | Qué muestra |
|---|---|
| **Globo** (minimizado) | Punto de color, red actual y, si hay una prueba con tiempo, la cuenta regresiva (`8 s → Sin red`). Tócalo para expandir y arrástralo para moverlo |
| **Barra** (expandida) | Red actual + Internet real (`OK (45 ms)` o el motivo de la falla) y una fila de chips de cambio rápido. Desplázala con el dedo si no caben todos |
| **Bandeja ▾** | Se abre con ▾: detalle de red móvil, último resultado, prueba activa con **Detener prueba** y los botones de prueba |

Colores del punto: verde = normal · azul = en prueba · amarillo = sin Internet real ·
rojo = desconectado · morado = el último cambio terminó con error.

Botones de la barra: **■** detiene la prueba en curso (visible solo durante una prueba),
**–** minimiza a globo y **×** cierra el panel.

### Cambio rápido

- **Perfil Wi-Fi** (★ = principal): conecta a esa red. Si no aparece en un escaneo reciente
  (fuera de alcance), lo indica en vez de cortar el Wi-Fi.
- **Móvil:** activa los datos y apaga el Wi-Fi. Necesita una SIM con datos.
- **Sin red:** apaga Wi-Fi y datos.
- **↺ Restaurar:** activa los datos y vuelve a la red principal.

Un cambio solo se da por bueno cuando la red nueva **tiene Internet real**. Si no ocurre,
el panel lo marca en rojo con el motivo (por ejemplo, *"HTTP 307 (redirección: ¿portal
cautivo?)"*). Si tocas otro cambio mientras uno espera confirmación, el nuevo lo reemplaza.

### Pruebas

| Prueba | Qué hace |
|---|---|
| Normal | Vuelve a la red principal y espera Internet real (línea base) |
| Pérdida 15 s | Corta Wi-Fi y datos 15 s, vuelve a la principal y mide la recuperación |
| Corte manual | Corta la red al iniciar y la mantiene cortada hasta que pulses Detener. Úsalo para cortar *durante* una operación |
| Recuperación | 3 ciclos de corte de 5 s; informa los tiempos mínimo, promedio y máximo de recuperación |
| Intermitencia | En bucle: X s con red y Y s sin red (configurable), volviendo a la principal, hasta Detener |
| Rotación | Recorre tus perfiles Wi-Fi (y Móvil si hay SIM) cada N s, hasta Detener |
| Latencia / Timeout (en gris) | No se pueden provocar sin root: requieren un modo de pruebas en la app de pagos |

- **Detener** responde al instante: restaura la red a la principal sin esperar confirmación.
- Mientras hay una prueba en curso, los botones de cambio rápido están bloqueados.
- Si se pierde la red durante una operación de pago, el resultado de esa operación es
  **desconocido**: un timeout no demuestra que haya fallado. Verifícalo con el sistema de
  pagos o la conciliación antes de reintentar o anular.

## 6. Historial y exportación

En la app (o con el botón **Historial** de la bandeja) se ven todos los eventos con hora,
identificador de prueba (`T-…` para pruebas, `Q-…` para cambios rápidos), estado de Wi-Fi
y móvil, e Internet real. **Exportar CSV** lo guarda en el POS. Para copiarlo al PC:

```sh
adb pull /sdcard/Android/data/com.example.pos_network_tester/files/logs/
```

El historial vive en memoria: se borra al cerrar la app o reinstalarla. Expórtalo antes.

## 7. Problemas frecuentes

| Síntoma | Solución |
|---|---|
| *"Shizuku no está en ejecución"* | El POS se reinició: repite el paso 4.2 |
| Un perfil dice *"no aparece en un escaneo reciente"* | La red está fuera de alcance o el SSID está mal escrito |
| *"no confirmado tras 45 s"* con Wi-Fi conectado | La red no tiene Internet real (por ejemplo, un portal cautivo de invitados) |
| "Móvil" no conecta | La SIM no está lista o no tiene datos: revisa "Móvil:" en la bandeja |
| El panel no aparece | Revisa el permiso de superposición (paso 4.4.1) |
| Aviso *"Modo de depuración…"* | Lo genera el sistema del POS mientras la depuración USB está activa: pulsa Aceptar |

## 8. Desinstalar

```sh
adb uninstall com.example.pos_network_tester
adb uninstall moe.shizuku.privileged.api   # si ya no lo necesitas
```
