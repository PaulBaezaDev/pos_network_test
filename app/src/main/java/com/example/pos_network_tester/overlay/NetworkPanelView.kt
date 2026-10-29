package com.example.pos_network_tester.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.pos_network_tester.network.ConnectivitySnapshot
import com.example.pos_network_tester.network.NetworkAction
import com.example.pos_network_tester.network.Transport
import com.example.pos_network_tester.network.WifiProfile
import com.example.pos_network_tester.privileged.ExecutorState
import com.example.pos_network_tester.simulation.ActionReport
import com.example.pos_network_tester.simulation.ActiveRun
import com.example.pos_network_tester.simulation.Scenario
import com.example.pos_network_tester.simulation.ScenarioKind

enum class OverallState(val label: String, val color: Int) {
    NORMAL("NORMAL", Color.parseColor("#2E7D32")),
    TESTING("EN PRUEBA", Color.parseColor("#1565C0")),
    NO_INTERNET("SIN INTERNET", Color.parseColor("#F9A825")),
    DISCONNECTED("DESCONECTADO", Color.parseColor("#C62828")),
    ERROR("ERROR", Color.parseColor("#6A1B9A"))
}

/**
 * Panel flotante compacto con Views nativas (sin Compose dentro del Service):
 * - Globo: red actual + cuenta regresiva.
 * - Barra: red actual y chips de cambio rápido en una fila desplazable.
 * - Bandeja "Pruebas": oculta por defecto; se despliega bajo la barra.
 */
class NetworkPanelView(context: Context) : FrameLayout(context) {

    val bubbleView: View
    val dragHandle: View

    private val barRoot: LinearLayout
    private val drawer: View

    private val bubbleDot = dot()
    private val bubbleName = text(12f, Color.WHITE, bold = true).apply { maxWidth = dp(120) }
    private val bubbleCountdown = text(11f, Color.parseColor("#FFD54F"))

    private val headerDot = dot()
    private val networkName = text(13f, Color.parseColor("#212121"), bold = true)
    private val networkDetail = text(11f, Color.parseColor("#616161"))
    private val countdownPill = text(11f, Color.WHITE, bold = true).apply {
        background = rounded(Color.parseColor("#1565C0"), 10)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        maxWidth = dp(130)
        visibility = View.GONE
    }
    private val headerStop = iconButton("■", Color.parseColor("#C62828")) { onStopScenario?.invoke() }
        .apply { visibility = View.GONE }
    private val drawerToggle = iconButton("▾", Color.parseColor("#424242")) { toggleDrawer() }

    private val quickRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private val detailsLine = text(11f, Color.parseColor("#616161"))
    private val reportLine = text(11f, Color.parseColor("#2E7D32"))
    private val runLine = text(12f, Color.parseColor("#1565C0"), bold = true)
    private val stopButton = chip("Detener prueba", Color.parseColor("#C62828"), Color.WHITE) {
        onStopScenario?.invoke()
    }
    private val runRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }
    private val scenarioGrid = GridLayout(context).apply { columnCount = 3 }

    private var profiles: List<WifiProfile> = emptyList()
    private var lastSnapshot: ConnectivitySnapshot? = null
    private var lastSsid: String? = null
    private var pendingLabel: String? = null
    private var drawerOpen = false
    private var executorWarning: String? = null

    var onCollapse: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onQuickAction: ((NetworkAction) -> Unit)? = null
    var onRestore: (() -> Unit)? = null
    var onStartScenario: ((Scenario) -> Unit)? = null
    var onStopScenario: (() -> Unit)? = null
    var onOpenLog: (() -> Unit)? = null
    var onSizeChanged: (() -> Unit)? = null

    init {
        bubbleView = buildBubble()
        val header = buildHeader()
        dragHandle = header
        drawer = buildDrawer()
        barRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#FAFAFA"), 12)
            elevation = dp(6).toFloat()
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val screenWidth = resources.displayMetrics.widthPixels
            layoutParams = LayoutParams(minOf(screenWidth - dp(16), dp(420)), ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(header)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(quickRow)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            })
            addView(drawer)
        }
        addView(barRoot)
        addView(bubbleView)
        setProfiles(emptyList())
        setExpanded(false)
    }

    fun setExpanded(expanded: Boolean) {
        bubbleView.visibility = if (expanded) View.GONE else View.VISIBLE
        barRoot.visibility = if (expanded) View.VISIBLE else View.GONE
        onSizeChanged?.invoke()
    }

    fun setProfiles(newProfiles: List<WifiProfile>) {
        profiles = newProfiles
        rebuildQuickRow()
    }

    fun updateStatus(snapshot: ConnectivitySnapshot, ssid: String?) {
        lastSnapshot = snapshot
        lastSsid = ssid
        val status = snapshot.status
        val name = when (status.activeTransport) {
            Transport.WIFI -> ssid ?: "Wi-Fi"
            Transport.CELLULAR -> "Móvil ${status.mobileGeneration.label}"
            Transport.ETHERNET -> "Ethernet"
            Transport.NONE -> "Sin red"
        }
        networkName.text = name
        bubbleName.text = name
        networkDetail.text = snapshot.internetLabel() + executorWarning?.let { " · ⚠ $it" }.orEmpty()
        networkDetail.setTextColor(if (snapshot.internetOk && executorWarning == null) GREEN else RED)
        detailsLine.text = "Móvil: ${status.mobileLabel()} · Tipo: ${status.mobileGeneration.label} · " +
            "Validación Android: " +
            if (!status.androidValidationEnabled) "desactivada" else if (status.isInternetValidated) "sí" else "no"
        rebuildQuickRow()
    }

    fun updateExecutor(state: ExecutorState) {
        executorWarning = if (state == ExecutorState.READY) null else state.label
        lastSnapshot?.let { updateStatus(it, lastSsid) }
    }

    fun updateOverallState(state: OverallState) {
        (headerDot.background as GradientDrawable).setColor(state.color)
        (bubbleDot.background as GradientDrawable).setColor(state.color)
    }

    fun updateRun(run: ActiveRun?, pending: String?, stopping: Boolean) {
        pendingLabel = pending
        runRow.visibility = if (run != null) View.VISIBLE else View.GONE
        headerStop.visibility = if (run != null && !stopping) View.VISIBLE else View.GONE
        if (run != null) {
            runLine.text = if (stopping) "Deteniendo ${run.scenario.shortLabel}…" else "▶ ${run.scenario.label}"
            stopButton.isEnabled = !stopping
            stopButton.alpha = if (stopping) 0.4f else 1f
        }
        rebuildQuickRow()
        onSizeChanged?.invoke()
    }

    fun updateReport(report: ActionReport?) {
        reportLine.text = report?.text.orEmpty()
        reportLine.visibility = if (report == null) View.GONE else View.VISIBLE
        reportLine.setTextColor(if (report?.isError == true) RED else GREEN)
    }

    /** null oculta la cuenta regresiva. */
    fun updateCountdown(text: String?) {
        countdownPill.text = text.orEmpty()
        countdownPill.visibility = if (text == null) View.GONE else View.VISIBLE
        bubbleCountdown.text = text.orEmpty()
        bubbleCountdown.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun toggleDrawer() {
        drawerOpen = !drawerOpen
        drawer.visibility = if (drawerOpen) View.VISIBLE else View.GONE
        drawerToggle.text = if (drawerOpen) "▴" else "▾"
        onSizeChanged?.invoke()
    }

    private fun rebuildQuickRow() {
        quickRow.removeAllViews()
        val status = lastSnapshot?.status
        profiles.forEach { profile ->
            val active = status?.activeTransport == Transport.WIFI && lastSsid == profile.ssid
            quickRow.addView(quickChip(
                (if (profile.isPrimary) "★ " else "") + profile.ssid,
                active,
                pendingLabel == NetworkAction.ConnectWifi(profile).label
            ) { onQuickAction?.invoke(NetworkAction.ConnectWifi(profile)) })
        }
        quickRow.addView(quickChip(
            "Móvil",
            status?.activeTransport == Transport.CELLULAR,
            pendingLabel == NetworkAction.MobileOnly.label
        ) { onQuickAction?.invoke(NetworkAction.MobileOnly) })
        quickRow.addView(quickChip(
            "Sin red",
            status?.activeTransport == Transport.NONE,
            pendingLabel == NetworkAction.NoNetwork.label,
            textColor = RED,
            activeColor = RED
        ) { onQuickAction?.invoke(NetworkAction.NoNetwork) })
        quickRow.addView(quickChip(
            "↺ Restaurar",
            active = false,
            pending = pendingLabel?.startsWith("Restaurar") == true
        ) { onRestore?.invoke() })
    }

    private fun buildBubble(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Color.parseColor("#263238"), 20)
        elevation = dp(4).toFloat()
        setPadding(dp(10), dp(6), dp(12), dp(6))
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(bubbleDot)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, 0, 0)
            // WRAP_CONTENT explícito: con MATCH_PARENT (por defecto en vertical) el globo no se
            // re-mide al cambiar de red y el nombre queda cortado al ancho del primer texto.
            val wrap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            addView(bubbleName, wrap())
            addView(bubbleCountdown.apply { visibility = View.GONE }, wrap())
        })
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(headerDot)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(networkName)
            addView(networkDetail)
        })
        addView(countdownPill)
        addView(headerStop)
        addView(drawerToggle)
        addView(iconButton("–", Color.parseColor("#424242")) { onCollapse?.invoke() })
        addView(iconButton("×", Color.parseColor("#424242")) { onClose?.invoke() })
    }

    private fun buildDrawer(): View {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(divider())
            addView(detailsLine)
            addView(reportLine.apply { visibility = View.GONE })
            runRow.addView(runLine.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            runRow.addView(stopButton)
            addView(runRow)
            addView(text(11f, Color.parseColor("#616161")).apply {
                text = "Pruebas (gris = requiere integración con la app de pagos)"
                setPadding(0, dp(4), 0, dp(2))
            })
            Scenario.entries.forEach { scenario ->
                val available = scenario.kind == ScenarioKind.REAL_NETWORK
                scenarioGrid.addView(chip(
                    scenario.shortLabel,
                    if (available) Color.parseColor("#E3F2FD") else Color.parseColor("#EEEEEE"),
                    if (available) Color.parseColor("#0D47A1") else Color.parseColor("#9E9E9E"),
                    inGrid = true
                ) { onStartScenario?.invoke(scenario) })
            }
            addView(scenarioGrid)
            addView(chip("Historial", Color.parseColor("#ECEFF1"), Color.parseColor("#263238")) {
                onOpenLog?.invoke()
            })
        }
        val maxHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        return MaxHeightScrollView(context, maxHeight).apply {
            addView(body)
            visibility = View.GONE
        }
    }

    private fun quickChip(
        label: String,
        active: Boolean,
        pending: Boolean,
        textColor: Int = Color.parseColor("#263238"),
        activeColor: Int = GREEN,
        onClick: () -> Unit
    ): View = TextView(context).apply {
        text = label
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(150)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        val (background, foreground) = when {
            pending -> Color.parseColor("#FFE082") to Color.parseColor("#263238")
            active -> activeColor to Color.WHITE
            else -> Color.parseColor("#ECEFF1") to textColor
        }
        this.background = rounded(background, 16)
        setTextColor(foreground)
        if (active) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun chip(
        label: String,
        backgroundColor: Int,
        textColor: Int,
        inGrid: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 11f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setTextColor(textColor)
        minHeight = dp(32)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        background = rounded(backgroundColor, 8)
        layoutParams = if (inGrid) {
            GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
        } else {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(2), dp(4), dp(2), dp(2)) }
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun iconButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 17f
            setTextColor(color)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun text(size: Float, color: Int, bold: Boolean = false): TextView = TextView(context).apply {
        textSize = size
        setTextColor(color)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dot(): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.GRAY)
        }
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
    }

    private fun divider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            bottomMargin = dp(4)
        }
        setBackgroundColor(Color.parseColor("#E0E0E0"))
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
        }
    }

    private companion object {
        val GREEN = Color.parseColor("#2E7D32")
        val RED = Color.parseColor("#C62828")
    }
}
