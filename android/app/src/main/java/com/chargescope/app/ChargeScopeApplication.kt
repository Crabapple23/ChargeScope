package com.chargescope.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.chargescope.app.service.SamplingForegroundService.Companion.CHANNEL_ID

class ChargeScopeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_sampling_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.channel_sampling_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
