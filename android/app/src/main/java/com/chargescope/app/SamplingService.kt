package com.chargescope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service with two roles:
 *  - manual recording (default intent), and
 *  - "armed" auto-record: idles without sampling, starts recording when a charger is
 *    connected and stops when it is removed.
 * Recording always stops when a safety threshold is hit. Thresholds only stop *recording*;
 * this app never controls charging.
 */
class SamplingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    @Volatile private var armed = false
    @Volatile private var suppressed = false
    private var receiverOn = false
    private var channelReady = false
    private var alertChannelReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    suppressed = false
                    if (armed && !recordingActive()) startRecording()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    suppressed = false
                    if (recordingActive()) stopRecording("充电器已断开")
                    afterStop()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun recordingActive() = job?.isActive == true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.init(applicationContext)
        Archive.init(applicationContext)
        Recorder.init(applicationContext)
        ensureForeground()
        AppLog.i("服务指令：${intent?.action ?: "手动开始"}")
        when (intent?.action) {
            ACTION_ARM -> {
                armed = true
                registerReceiverOnce()
                if (!recordingActive()) {
                    if (!suppressed && Collector.battery(applicationContext).isPlugged) startRecording() else idle()
                }
            }
            ACTION_DISARM -> {
                armed = false
                if (!recordingActive()) stopSelf()
            }
            ACTION_STOP_REC -> {
                stopRecording("已手动停止")
                suppressed = true
                afterStop()
            }
            else -> {
                registerReceiverOnce()
                suppressed = false
                if (!recordingActive()) startRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun registerReceiverOnce() {
        if (receiverOn) return
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverOn = true
    }

    @Synchronized
    private fun startRecording() {
        if (recordingActive()) return
        val app = applicationContext
        Recorder.open(app)
        Sampler.setRecording(true)
        job = scope.launch {
            val started = SystemClock.elapsedRealtime()
            var n = 0
            while (isActive) {
                Sampler.sample(app)
                val s = Sampler.state.value.latest
                val why = violation(app, s, started)
                if (why != null) {
                    stopRecording(why)
                    notifySafetyThreshold(why)
                    suppressed = true
                    afterStop()
                    return@launch
                }
                if (n++ % 5 == 0) {
                    val p = s?.let { Analyzer.powerMw(it, Prefs.dualCell(app)) }
                    postNote(
                        "记录中 · 电量 ${s?.levelPct ?: "—"}%" + (p?.let { "  ·  %.1f W".format(it / 1000.0) } ?: ""),
                        showStopAction = true,
                    )
                }
                delay(Prefs.intervalMs(app))
            }
        }
    }

    @Synchronized
    private fun stopRecording(reason: String?) {
        job?.cancel()
        job = null
        Recorder.close()
        RootScanner.close()
        Sampler.setRecording(false)
        Sampler.setNotice(reason)
    }

    /** After recording ended: stay armed (idle) or shut the service down. */
    private fun afterStop() {
        if (armed) idle() else if (!recordingActive()) stopSelf()
    }

    private fun idle() = postNote("自动记录已开启 · 等待连接充电器")

    private fun violation(ctx: Context, s: Snapshot?, startedAt: Long): String? {
        if (s == null) return null
        val maxTemp = Prefs.long(ctx, Prefs.TEMP, 45L)
        val maxMin = Prefs.long(ctx, Prefs.MINUTES, 120L)
        val soc = Prefs.long(ctx, Prefs.SOC, 100L)
        val maxMb = Prefs.long(ctx, Prefs.FILE_MB, 8L)
        val t = s.tempDeci
        return when {
            t != null && t >= maxTemp * 10 -> "电池温度达到 %.1f °C，已停止记录（阈值 %d °C）".format(t / 10.0, maxTemp)
            maxMin > 0 && SystemClock.elapsedRealtime() - startedAt >= maxMin * 60_000L ->
                "已达到最长记录时长 $maxMin 分钟"
            soc in 1..100 && (s.levelPct ?: 0) >= soc -> "电量已达到 $soc%"
            Recorder.approxBytes >= maxMb * 1024L * 1024L -> "记录文件已达到 $maxMb MB 上限"
            else -> null
        }
    }

    private fun ensureForeground() {
        if (!channelReady) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "充电采样", NotificationManager.IMPORTANCE_LOW))
            channelReady = true
        }
        ServiceCompat.startForeground(
            this, ID, notification("充了个电 运行中"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun notifySafetyThreshold(reason: String) {
        mainHandler.post {
            try {
                Toast.makeText(applicationContext, reason, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
        }
        if (!hasNotificationPermission(this)) return
        val nm = getSystemService(NotificationManager::class.java)
        if (!alertChannelReady) {
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "安全阈值提醒", NotificationManager.IMPORTANCE_DEFAULT),
            )
            alertChannelReady = true
        }
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("安全阈值已触发")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(ALERT_ID, n)
    }

    private fun postNote(text: String, showStopAction: Boolean = false) =
        getSystemService(NotificationManager::class.java).notify(ID, notification(text, showStopAction))

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopActionIntent(): PendingIntent {
        val intent = Intent(this, SamplingService::class.java).apply {
            action = ACTION_STOP_REC
        }
        return PendingIntent.getService(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notification(text: String, showStopAction: Boolean = false): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("充了个电")
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (showStopAction) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "停止记录",
                    stopActionIntent(),
                ).build(),
            )
        }
        return builder.build()
    }

    override fun onDestroy() {
        if (receiverOn) {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverOn = false
        }
        job?.cancel()
        scope.cancel()
        Recorder.close()
        RootScanner.close()
        Sampler.setRecording(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_ARM = "com.chargescope.hanhan.app.ARM"
        const val ACTION_DISARM = "com.chargescope.hanhan.app.DISARM"
        const val ACTION_STOP_REC = "com.chargescope.hanhan.app.STOP_REC"
        private const val CHANNEL = "sampling"
        private const val ALERT_CHANNEL = "safety_alert"
        private const val ID = 1
        private const val ALERT_ID = 2

        fun send(ctx: Context, action: String?) {
            val i = Intent(ctx, SamplingService::class.java)
            if (action != null) i.action = action
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
