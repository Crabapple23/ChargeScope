package com.chargescope.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.chargescope.app.MainActivity
import com.chargescope.app.R
import com.chargescope.app.collector.BatteryApiCollector
import com.chargescope.app.collector.RootSysfsCollector
import com.chargescope.app.data.Repository
import com.chargescope.app.data.SettingsStore
import com.chargescope.app.domain.RustBridge
import com.chargescope.app.domain.model.AnalysisBundle
import com.chargescope.app.domain.model.ChargingSnapshot
import com.chargescope.app.root.RootBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that samples battery + optional Root sysfs, calls Rust for
 * analysis, and persists snapshots in batches. Collection, analysis and DB
 * writes are all off the main thread.
 */
class SamplingForegroundService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private lateinit var repository: Repository
    private lateinit var settings: SettingsStore
    private lateinit var batteryCollector: BatteryApiCollector
    private lateinit var rootBridge: RootBridge
    private lateinit var rootCollector: RootSysfsCollector

    private var sessionId: Long = -1L
    private var lastRootScanMs = 0L
    private var rootAvailable = false
    private val pendingSnapshots = mutableListOf<ChargingSnapshot>()
    private val recentSnapshots = ArrayDeque<ChargingSnapshot>()
    private var lastAnalysis: AnalysisBundle? = null

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
        settings = SettingsStore(this)
        batteryCollector = BatteryApiCollector(this)
        rootBridge = RootBridge()
        rootCollector = RootSysfsCollector(rootBridge)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), foregroundType())
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { finishSession() }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (sessionId < 0) scope.launch { startLoop() }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.launch { finishSession() }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startLoop() {
        sessionId = repository.startSession()
        val rootEnabled = settings.rootEnabled.first()
        if (rootEnabled) {
            rootAvailable = rootBridge.probe()
            if (!rootAvailable) {
                collectErrors += "Root 探测失败；继续无 Root 采集"
            }
        }

        while (true) {
            val period = settings.samplePeriodMs.first().coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)
            val useRoot = settings.rootEnabled.first() && rootAvailable

            val api = batteryCollector.collect(rootAvailable)
            collectErrors.addAll(api.errors)

            var rootRecords = emptyList<com.chargescope.app.domain.model.SourceRecord>()
            if (useRoot && SystemClock.elapsedRealtime() - lastRootScanMs >= ROOT_SCAN_INTERVAL_MS) {
                val scan = rootCollector.scan()
                rootRecords = scan.records
                collectErrors.addAll(scan.errors)
                lastRootScanMs = SystemClock.elapsedRealtime()
            }

            val snapshot = api.snapshot.copy(
                sourceRecords = api.snapshot.sourceRecords + rootRecords,
                rootAvailable = useRoot,
            )
            pendingSnapshots += snapshot
            recentSnapshots.addLast(snapshot)
            while (recentSnapshots.size > MAX_WINDOW) recentSnapshots.removeFirst()

            // Call Rust on a bounded window, not the entire history.
            if (recentSnapshots.size >= MIN_WINDOW_FOR_ANALYSIS) {
                lastAnalysis = RustBridge.analyze(
                    snapshots = recentSnapshots.toList(),
                    profile = null, // device profile is attached in P5; unknown for now
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
                _analysis.value = lastAnalysis
                _latestSnapshot.value = snapshot
            }

            if (pendingSnapshots.size >= DB_BATCH_SIZE) flush()

            updateNotification(snapshot)
            delay(period)
        }
    }

    private suspend fun flush() {
        if (pendingSnapshots.isEmpty()) return
        val batch = pendingSnapshots.toList()
        pendingSnapshots.clear()
        repository.saveSnapshots(sessionId, batch)
        lastAnalysis?.let { repository.saveAnalysis(sessionId, it) }
    }

    private suspend fun finishSession() {
        if (sessionId < 0) return
        flush()
        val analysis = lastAnalysis
        repository.closeSession(
            sessionId = sessionId,
            peak = analysis?.metrics?.peakBatteryPowerUw,
            avg = analysis?.metrics?.avgBatteryPowerUw,
            count = recentSnapshots.size,
        )
        sessionId = -1L
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_sampling))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(intent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(snapshot: ChargingSnapshot) {
        val text = buildString {
            append("SOC ")
            append(snapshot.socPercent?.let { "$it%" } ?: getString(R.string.unavailable))
            snapshot.batteryPowerUw?.let { append(" · ${it / 1_000_000} mW") }
            if (snapshot.rootAvailable) append(" · Root 只读")
        }
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(intent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0

    companion object {
        const val CHANNEL_ID = "chargescope_sampling"
        const val ACTION_START = "com.chargescope.app.START_SAMPLING"
        const val ACTION_STOP = "com.chargescope.app.STOP_SAMPLING"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_PERIOD_MS = 1_000L
        private const val MAX_PERIOD_MS = 30_000L
        private const val ROOT_SCAN_INTERVAL_MS = 10_000L
        private const val DB_BATCH_SIZE = 12
        private const val MAX_WINDOW = 120
        private const val MIN_WINDOW_FOR_ANALYSIS = 2

        private val _latestSnapshot = MutableStateFlow<ChargingSnapshot?>(null)
        val latestSnapshot: StateFlow<ChargingSnapshot?> = _latestSnapshot

        private val _analysis = MutableStateFlow<AnalysisBundle?>(null)
        val analysis: StateFlow<AnalysisBundle?> = _analysis

        private val collectErrors = mutableListOf<String>()
        val errors: List<String> get() = collectErrors.toList()

        fun start(context: Context) {
            val intent = Intent(context, SamplingForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SamplingForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
