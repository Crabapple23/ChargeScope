package com.chargescope.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Optional Shizuku channel (adb-shell privileges). Read-only: reads sysfs + dumpsys battery. */
object ShizukuBridge {
    private const val REQ = 1001

    enum class State(val label: String) {
        NOT_RUNNING("Shizuku 未运行（请先在 Shizuku 应用中启动）"),
        NO_PERMISSION("Shizuku 已运行，尚未授权本应用"),
        READY("Shizuku 已就绪"),
    }

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> State.NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
            else -> State.NO_PERMISSION
        }
    } catch (_: Throwable) {
        State.NOT_RUNNING
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQ)
        } catch (_: Throwable) {
        }
    }

    @Volatile private var svc: IShellService? = null
    @Volatile private var binding = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = IShellService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            svc = null
            binding = false
        }
    }

    private fun args(ctx: Context) =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
            .processNameSuffix("shell")
            .debuggable(false)
            .daemon(false)
            .version(1)

    private suspend fun ensureBound(ctx: Context) {
        if (svc?.asBinder()?.isBinderAlive == true) return
        if (!binding) {
            binding = true
            Shizuku.bindUserService(args(ctx), conn)
        }
        repeat(50) {
            if (svc != null) return
            delay(100)
        }
        binding = false
    }

    /** Releases the Shizuku user service process when Shizuku mode is disabled. */
    fun unbind(ctx: Context) {
        val current = svc
        svc = null
        binding = false
        if (current != null) {
            try { Shizuku.unbindUserService(args(ctx.applicationContext), conn, true) } catch (_: Throwable) {}
        }
    }

    suspend fun scan(ctx: Context): RootScanner.Result = withContext(Dispatchers.IO) {
        val st = state()
        if (st != State.READY) return@withContext RootScanner.Result(emptyList(), st.label)
        try {
            ensureBound(ctx.applicationContext)
            val s = svc ?: return@withContext RootScanner.Result(emptyList(), "Shizuku 服务连接超时")
            RootScanner.interpret(s.run(""), setOf("2000", "0"))
        } catch (e: Throwable) {
            svc = null
            binding = false
            RootScanner.Result(emptyList(), "Shizuku 调用失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }
}
