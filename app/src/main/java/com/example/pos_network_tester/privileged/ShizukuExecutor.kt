package com.example.pos_network_tester.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.example.pos_network_tester.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

enum class ExecutorState(val label: String) {
    NOT_RUNNING("Shizuku no está en ejecución"),
    UNSUPPORTED("Versión de Shizuku demasiado antigua"),
    PERMISSION_REQUIRED("Falta autorizar esta app en Shizuku"),
    CONNECTING("Conectando con Shizuku…"),
    READY("Shizuku listo")
}

data class CommandResult(val success: Boolean, val output: String)

/**
 * Puente con Shizuku: una herramienta explícita y autorizada por el usuario que
 * concede a esta app el mismo nivel de privilegio que `adb shell`. Solo se
 * usan las operaciones cerradas de [IPrivilegedShell].
 */
object ShizukuExecutor {

    private const val PERMISSION_REQUEST_CODE = 4711

    private val _state = MutableStateFlow(ExecutorState.NOT_RUNNING)
    val state: StateFlow<ExecutorState> = _state.asStateFlow()

    @Volatile
    private var shell: IPrivilegedShell? = null
    private var initialized = false
    private var bindRequested = false
    private lateinit var userServiceArgs: Shizuku.UserServiceArgs

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshState() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shell = null
        bindRequested = false
        refreshState()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == PERMISSION_REQUEST_CODE) refreshState()
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                shell = IPrivilegedShell.Stub.asInterface(binder)
                _state.value = ExecutorState.READY
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shell = null
            bindRequested = false
            refreshState()
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        userServiceArgs = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, PrivilegedShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshState()
    }

    fun refreshState() {
        _state.value = when {
            !Shizuku.pingBinder() -> {
                shell = null
                ExecutorState.NOT_RUNNING
            }
            Shizuku.isPreV11() -> ExecutorState.UNSUPPORTED
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                ExecutorState.PERMISSION_REQUIRED
            shell != null -> ExecutorState.READY
            else -> {
                if (!bindRequested) {
                    bindRequested = true
                    Shizuku.bindUserService(userServiceArgs, connection)
                }
                ExecutorState.CONNECTING
            }
        }
    }

    /** Devuelve false si el usuario bloqueó el permiso y hay que concederlo desde la app Shizuku. */
    fun requestPermission(): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.shouldShowRequestPermissionRationale()) return false
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        return true
    }

    suspend fun connectWifi(ssid: String, security: String, passphrase: String): CommandResult =
        call { it.connectWifi(ssid, security, passphrase) }

    suspend fun setWifiEnabled(enabled: Boolean): CommandResult =
        call { it.setWifiEnabled(enabled) }

    suspend fun setMobileDataEnabled(enabled: Boolean): CommandResult =
        call { it.setMobileDataEnabled(enabled) }

    suspend fun currentWifiSsid(): String? = withContext(Dispatchers.IO) {
        val current = shell ?: return@withContext null
        runCatching { current.currentWifiSsid() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun call(block: (IPrivilegedShell) -> String): CommandResult =
        withContext(Dispatchers.IO) {
            val current = shell
                ?: return@withContext CommandResult(false, _state.value.label)
            try {
                val raw = block(current)
                val code = raw.substringBefore('|')
                CommandResult(code == "0", raw.substringAfter('|'))
            } catch (e: Exception) {
                // Errores de binder o validaciones del servicio (SSID vacío, falta clave…).
                CommandResult(false, e.message ?: e.javaClass.simpleName)
            }
        }
}
