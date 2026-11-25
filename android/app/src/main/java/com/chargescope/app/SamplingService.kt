package com.chargescope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SamplingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "充电采样", NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(
            this, ID, notification("正在采集充电数据…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        val app = applicationContext
        Recorder.open(app)
        Sampler.setRecording(true)
        job = scope.launch {
            var n = 0
            while (isActive) {
                Sampler.sample(app)
                if (n++ % 5 == 0) {
                    val s = Sampler.state.value.latest
                    val p = s?.let { Analyzer.powerMw(it) }
                    val text = "电量 ${s?.levelPct ?: "—"}%" + (p?.let { "  ·  %.1f W".format(it / 1000.0) } ?: "")
                    nm.notify(ID, notification(text))
                }
                delay(Prefs.intervalMs(app))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("ChargeScope")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        Sampler.setRecording(false)
        Recorder.close()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "sampling"
        const val ID = 1
    }
}
