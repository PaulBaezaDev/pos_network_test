package com.example.pos_network_tester.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.pos_network_tester.MainActivity
import com.example.pos_network_tester.R
import com.example.pos_network_tester.logging.EventType
import com.example.pos_network_tester.logging.TestEventLogger
import com.example.pos_network_tester.network.ConnectivitySnapshot
import com.example.pos_network_tester.network.InternetProbe
import com.example.pos_network_tester.network.NetworkStatus
import com.example.pos_network_tester.network.NetworkStatusReader
import com.example.pos_network_tester.network.NetworkSwitcher
import com.example.pos_network_tester.network.ProbeSettings
import com.example.pos_network_tester.network.Transport
import com.example.pos_network_tester.network.WifiProfileStore
import com.example.pos_network_tester.privileged.ExecutorState
import com.example.pos_network_tester.privileged.ShizukuExecutor
import com.example.pos_network_tester.simulation.ScenarioController
import com.example.pos_network_tester.simulation.ScenarioSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Servicio en primer plano dueño del panel flotante (TYPE_APPLICATION_OVERLAY).
 * Conecta el monitor de red, el ejecutor privilegiado y el controlador de
 * escenarios con la vista, y libera todo en onDestroy para evitar fugas.
 */
class FloatingPanelService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "pos_network_tester_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val TAP_MOVE_THRESHOLD_PX = 12
        private const val PERIODIC_REFRESH_MS = 10_000L
        private const val PROBE_INTERVAL_MS = 5_000L

        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FloatingPanelService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPanelService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var panelView: NetworkPanelView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val networkStatusReader by lazy { NetworkStatusReader(applicationContext) }
    private val switcher = NetworkSwitcher()
    private val ssidFlow = MutableStateFlow<String?>(null)
    private val internetProbe by lazy { InternetProbe(applicationContext) }
    private val snapshotFlow by lazy {
        combine(networkStatusReader.statusFlow, internetProbe.resultFlow, ::ConnectivitySnapshot)
            .stateIn(serviceScope, SharingStarted.Eagerly, ConnectivitySnapshot(NetworkStatus(), null))
    }
    private lateinit var scenarioController: ScenarioController

    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startAsForeground()
        scenarioController = ScenarioController(
            scope = serviceScope,
            network = switcher,
            snapshot = snapshotFlow,
            currentSsid = { ssidFlow.value },
            profiles = { WifiProfileStore.profiles.value },
            config = { ScenarioSettings.config.value }
        )
        attachOverlayView()
        networkStatusReader.start()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        scenarioController.shutdown()
        networkStatusReader.stop()
        serviceScope.cancel()
        if (::panelView.isInitialized) {
            runCatching { windowManager.removeView(panelView) }
        }
        super.onDestroy()
    }

    private fun observeState() {
        serviceScope.launch {
            var previous: NetworkStatus? = null
            networkStatusReader.statusFlow.collect { status ->
                ssidFlow.value = if (status.wifiConnected) switcher.currentWifiSsid() else null
                if (previous != null) {
                    // Instantánea explícita: snapshotFlow se actualiza un instante después que statusFlow.
                    logConnectivity(
                        ConnectivitySnapshot(status, internetProbe.resultFlow.value),
                        status.lastChangeDescription
                    )
                }
                previous = status
            }
        }
        serviceScope.launch {
            // Sondea en cada cambio de red y, si no hay cambios, cada PROBE_INTERVAL_MS.
            while (isActive) {
                internetProbe.probeNow(ProbeSettings.url.value)
                withTimeoutOrNull(PROBE_INTERVAL_MS) { networkStatusReader.statusFlow.drop(1).first() }
            }
        }
        serviceScope.launch {
            // Registra cambios de resultado (OK <-> falla) y de motivo de falla, ignorando la latencia.
            var previousKey: String? = null
            snapshotFlow.collect { snapshot ->
                if (!snapshot.hasFreshProbe) return@collect
                val key = if (snapshot.internetOk) "ok" else snapshot.probe?.error
                if (previousKey != null && previousKey != key) {
                    logConnectivity(snapshot, "Internet real: ${snapshot.internetLabel()}")
                }
                previousKey = key
            }
        }
        serviceScope.launch {
            combine(snapshotFlow, ssidFlow) { snapshot, ssid -> snapshot to ssid }
                .collect { (snapshot, ssid) -> panelView.updateStatus(snapshot, ssid) }
        }
        serviceScope.launch {
            ShizukuExecutor.state.collect { state ->
                panelView.updateExecutor(state)
                if (state == ExecutorState.READY && networkStatusReader.statusFlow.value.wifiConnected) {
                    ssidFlow.value = switcher.currentWifiSsid()
                }
            }
        }
        serviceScope.launch {
            WifiProfileStore.profiles.collect { panelView.setProfiles(it) }
        }
        serviceScope.launch {
            combine(
                scenarioController.activeRun,
                scenarioController.pendingAction,
                scenarioController.stopping
            ) { run, pending, stopping -> Triple(run, pending, stopping) }
                .collect { (run, pending, stopping) -> panelView.updateRun(run, pending, stopping) }
        }
        serviceScope.launch {
            scenarioController.countdown.collectLatest { countdown ->
                if (countdown == null) {
                    panelView.updateCountdown(null)
                    return@collectLatest
                }
                while (true) {
                    val remainingMs = countdown.targetElapsedMs - scenarioController.clock()
                    val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
                    panelView.updateCountdown("$seconds s → ${countdown.nextLabel}")
                    delay((remainingMs % 1000).takeIf { it > 0 } ?: 1000L)
                }
            }
        }
        serviceScope.launch {
            combine(
                snapshotFlow,
                scenarioController.activeRun,
                scenarioController.pendingAction,
                scenarioController.lastReport
            ) { snapshot, run, pending, report ->
                panelView.updateReport(report)
                when {
                    run != null || pending != null -> OverallState.TESTING
                    report?.isError == true -> OverallState.ERROR
                    snapshot.status.activeTransport == Transport.NONE -> OverallState.DISCONNECTED
                    !snapshot.internetOk -> OverallState.NO_INTERNET
                    else -> OverallState.NORMAL
                }
            }.collect { panelView.updateOverallState(it) }
        }
        serviceScope.launch {
            // Captura cambios que no disparan NetworkCallback (p. ej. 4G -> 3G o retirar la SIM).
            while (isActive) {
                delay(PERIODIC_REFRESH_MS)
                networkStatusReader.refreshNow("Actualización periódica")
            }
        }
    }

    private fun logConnectivity(snapshot: ConnectivitySnapshot, message: String) {
        val run = scenarioController.activeRun.value
        TestEventLogger.log(
            EventType.CONNECTIVITY_CHANGE,
            snapshot,
            ssidFlow.value,
            message,
            testId = run?.testId,
            scenarioLabel = run?.scenario?.label
        )
    }

    private fun startAsForeground() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "POS Network Tester",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("POS Network Tester activo")
            .setContentText("Panel de diagnóstico de red visible sobre otras apps")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun attachOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        panelView = NetworkPanelView(this).apply {
            onCollapse = { setExpanded(false) }
            onClose = { stopSelf() }
            onQuickAction = { action -> scenarioController.quickSwitch(action)?.let(::toast) }
            onRestore = {
                scenarioController.quickSwitch(scenarioController.restoreAction())?.let(::toast)
            }
            onSizeChanged = { keepOnScreen() }
            onStartScenario = { scenario -> scenarioController.startScenario(scenario)?.let(::toast) }
            onStopScenario = { scenarioController.stopScenario() }
            onOpenLog = { openHostApp() }
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: el panel nunca roba el foco, el teclado ni el botón Atrás a la app de pagos.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(120)
        }

        panelView.bubbleView.setOnTouchListener { _, event -> handleDrag(event, isBubble = true) }
        panelView.dragHandle.setOnTouchListener { _, event -> handleDrag(event, isBubble = false) }
        windowManager.addView(panelView, layoutParams)
    }

    private fun handleDrag(event: MotionEvent, isBubble: Boolean): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = layoutParams.x
                downParamY = layoutParams.y
            }
            MotionEvent.ACTION_MOVE -> {
                layoutParams.x = downParamX + (event.rawX - downRawX).toInt()
                layoutParams.y = downParamY + (event.rawY - downRawY).toInt()
                runCatching { windowManager.updateViewLayout(panelView, layoutParams) }
            }
            MotionEvent.ACTION_UP -> {
                val moved = abs(event.rawX - downRawX) > TAP_MOVE_THRESHOLD_PX ||
                    abs(event.rawY - downRawY) > TAP_MOVE_THRESHOLD_PX
                if (!moved && isBubble) panelView.setExpanded(true)
                keepOnScreen()
            }
            else -> return false
        }
        return true
    }

    /** Tras expandir, abrir la bandeja o arrastrar, evita que el panel quede fuera de la pantalla. */
    private fun keepOnScreen() {
        if (!::layoutParams.isInitialized) return
        panelView.post {
            val metrics = resources.displayMetrics
            val x = layoutParams.x.coerceIn(0, (metrics.widthPixels - panelView.width).coerceAtLeast(0))
            val y = layoutParams.y.coerceIn(0, (metrics.heightPixels - panelView.height).coerceAtLeast(0))
            if (x != layoutParams.x || y != layoutParams.y) {
                layoutParams.x = x
                layoutParams.y = y
                runCatching { windowManager.updateViewLayout(panelView, layoutParams) }
            }
        }
    }

    private fun openHostApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
