package com.chargescope.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Optional Shizuku channel (adb-shell privileges). Read-only: reads sysfs + dumpsys battery. */
object ShizukuBridge {
    private const val REQ = 1001

    enum class State(val label: String, val labelEn: String) {
        NOT_RUNNING("Shizuku 未运行（请先在 Shizuku 应用中启动）", "Shizuku not running (start it in the Shizuku app first)"),
        NO_PERMISSION("Shizuku 已运行，尚未授权本应用", "Shizuku running, permission not granted"),
        READY("Shizuku 已就绪", "Shizuku ready");

        fun display(isEn: Boolean): String = if (isEn) labelEn else label
    }

    private val _state = MutableStateFlow(State.NOT_RUNNING)
    val stateFlow = _state.asStateFlow()

    @Volatile private var listenersAdded = false
    @Volatile private var svc: IShellService? = null
    @Volatile private var pendingBind: CompletableDeferred<IShellService?>? = null

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshState()
    }
    private val receivedListener = Shizuku.OnBinderReceivedListener {
        refreshState()
    }
    private val deadListener = Shizuku.OnBinderDeadListener {
        svc = null
        pendingBind?.complete(null)
        pendingBind = null
        _state.value = State.NOT_RUNNING
    }

    private fun ensureListeners() {
        if (listenersAdded) return
        synchronized(this) {
            if (listenersAdded) return
            try {
                Shizuku.addRequestPermissionResultListener(permListener)
                Shizuku.addBinderReceivedListenerSticky(receivedListener)
                Shizuku.addBinderDeadListener(deadListener)
                listenersAdded = true
            } catch (_: Throwable) {
            }
        }
    }

    fun state(): State {
        ensureListeners()
        return try {
            when {
                !Shizuku.pingBinder() || Shizuku.isPreV11() -> State.NOT_RUNNING
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
                else -> State.NO_PERMISSION
            }
        } catch (_: Throwable) {
            State.NOT_RUNNING
        }
    }

    fun refreshState(): State {
        val s = state()
        _state.value = s
        return s
    }

    fun requestPermission() {
        ensureListeners()
        try {
            Shizuku.requestPermission(REQ)
        } catch (_: Throwable) {
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val iface = IShellService.Stub.asInterface(binder)
            svc = iface
            pendingBind?.complete(iface)
            pendingBind = null
        }

        override fun onServiceDisconnected(name: ComponentName) {
            svc = null
            pendingBind?.complete(null)
            pendingBind = null
        }
    }

    private fun args(ctx: Context) =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
            .processNameSuffix("shell")
            .debuggable(false)
            .daemon(false)
            .version(1)

    private suspend fun ensureBound(ctx: Context): IShellService? {
        svc?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val deferred = synchronized(this) {
            svc?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            pendingBind ?: CompletableDeferred<IShellService?>().also {
                pendingBind = it
                Shizuku.bindUserService(args(ctx), conn)
            }
        }
        val bound = withTimeoutOrNull(5000L) { deferred.await() }
        if (bound == null) {
            synchronized(this) { if (pendingBind === deferred) pendingBind = null }
        }
        return bound
    }

    /** Releases the Shizuku user service process when Shizuku mode is disabled. */
    fun unbind(ctx: Context) {
        val current = svc
        svc = null
        pendingBind?.complete(null)
        pendingBind = null
        if (current != null) {
            try { Shizuku.unbindUserService(args(ctx.applicationContext), conn, true) } catch (_: Throwable) {}
        }
    }

    suspend fun scan(ctx: Context): RootScanner.Result = withContext(Dispatchers.IO) {
        val st = refreshState()
        if (st != State.READY) return@withContext RootScanner.Result(emptyList(), st.label)
        try {
            val s = ensureBound(ctx.applicationContext)
                ?: return@withContext RootScanner.Result(emptyList(), "Shizuku 服务连接超时")
            RootScanner.interpret(s.run(""), setOf("2000", "0"))
        } catch (e: Throwable) {
            svc = null
            RootScanner.Result(emptyList(), "Shizuku 调用失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    suspend fun deepProbe(ctx: Context): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val st = refreshState()
        if (st != State.READY) return@withContext null to st.label
        try {
            val s = ensureBound(ctx.applicationContext) ?: return@withContext null to "Shizuku 服务连接超时"
            s.run("deep") to null
        } catch (e: Throwable) {
            svc = null
            null to ("Shizuku 深度扫描失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }
}
