package com.chargescope.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "chargescope_settings")

class SettingsStore(private val context: Context) {

    companion object {
        private val KEY_SAMPLE_PERIOD = intPreferencesKey("sample_period_ms")
        private val KEY_ROOT_ENABLED = booleanPreferencesKey("root_enabled")
        private val KEY_REALTIME_MODE = booleanPreferencesKey("realtime_mode")
        private val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications")
        private val KEY_RETENTION_DAYS = intPreferencesKey("retention_days")
        private const val DEFAULT_PERIOD_MS = 3_000
        private const val REALTIME_PERIOD_MS = 1_000
    }

    val samplePeriodMs: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_SAMPLE_PERIOD] ?: DEFAULT_PERIOD_MS
    }

    val rootEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ROOT_ENABLED] ?: false
    }

    val realtimeMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_REALTIME_MODE] ?: false
    }

    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATIONS] ?: true
    }

    val retentionDays: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_RETENTION_DAYS] ?: 30
    }

    suspend fun setRootEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ROOT_ENABLED] = enabled }
    }

    suspend fun setRealtimeMode(enabled: Boolean) {
        context.dataStore.edit {
            it[KEY_REALTIME_MODE] = enabled
            it[KEY_SAMPLE_PERIOD] = if (enabled) REALTIME_PERIOD_MS else DEFAULT_PERIOD_MS
        }
    }

    suspend fun setSamplePeriod(ms: Int) {
        context.dataStore.edit { it[KEY_SAMPLE_PERIOD] = ms.coerceIn(1_000, 30_000) }
    }

    suspend fun setNotifications(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFICATIONS] = enabled }
    }

    suspend fun setRetentionDays(days: Int) {
        context.dataStore.edit { it[KEY_RETENTION_DAYS] = days.coerceIn(1, 365) }
    }
}
