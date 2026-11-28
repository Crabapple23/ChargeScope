package com.chargescope.app

import android.content.Context

object Prefs {
    const val TEMP = "max_temp_c"
    const val MINUTES = "max_minutes"
    const val SOC = "stop_soc"
    const val FILE_MB = "max_file_mb"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("cs", Context.MODE_PRIVATE)
    fun long(c: Context, k: String, d: Long): Long = sp(c).getLong(k, d)
    fun setLong(c: Context, k: String, v: Long) = sp(c).edit().putLong(k, v).apply()
    fun intervalMs(c: Context): Long = sp(c).getLong("interval", 2000L)
    fun setIntervalMs(c: Context, v: Long) = sp(c).edit().putLong("interval", v).apply()

    /** Privileged channel: 0 = off, 1 = root (Magisk/KernelSU/APatch), 2 = Shizuku. */
    fun privMode(c: Context): Int {
        val s = sp(c)
        return if (s.contains("priv")) s.getInt("priv", 0) else if (s.getBoolean("root", false)) 1 else 0
    }
    fun setPrivMode(c: Context, v: Int) = sp(c).edit().putInt("priv", v).apply()

    fun autoRecord(c: Context): Boolean = sp(c).getBoolean("auto", true)
    fun setAutoRecord(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto", v).apply()

    /** 0 = follow system (default), 1 = light, 2 = dark, 3 = scheduled */
    fun themeMode(c: Context): Int = sp(c).getInt("theme", 0)
    fun setThemeMode(c: Context, v: Int) = sp(c).edit().putInt("theme", v).apply()

    /** Minute of day [0..1439] when scheduled dark theme starts (default 22:00). */
    fun darkStartMin(c: Context): Int = sp(c).getInt("dark_start_min", 22 * 60)
    fun setDarkStartMin(c: Context, v: Int) = sp(c).edit().putInt("dark_start_min", v.coerceIn(0, 1439)).apply()

    /** Minute of day [0..1439] when scheduled dark theme ends / light starts (default 07:00). */
    fun darkEndMin(c: Context): Int = sp(c).getInt("dark_end_min", 7 * 60)
    fun setDarkEndMin(c: Context, v: Int) = sp(c).edit().putInt("dark_end_min", v.coerceIn(0, 1439)).apply()

    /** 0 = small (0.88x), 1 = normal (1.0x, default), 2 = large (1.15x), 3 = xlarge (1.3x) */
    fun fontTier(c: Context): Int = sp(c).getInt("font_tier", 1)
    fun setFontTier(c: Context, v: Int) = sp(c).edit().putInt("font_tier", v).apply()

    /** "zh" (default) or "en" */
    fun lang(c: Context): String = sp(c).getString("lang", "zh") ?: "zh"
    fun setLang(c: Context, v: String) = sp(c).edit().putString("lang", v).apply()

    /** 0 = GitHub Releases (default), 1 = GitHub Actions */
    fun updateSource(c: Context): Int = sp(c).getInt("update_src", 0)
    fun setUpdateSource(c: Context, v: Int) = sp(c).edit().putInt("update_src", v).apply()

    fun predictiveBack(c: Context): Boolean = sp(c).getBoolean("pred_back", true)
    fun setPredictiveBack(c: Context, v: Boolean) = sp(c).edit().putBoolean("pred_back", v).apply()

    fun dynamicColor(c: Context): Boolean = sp(c).getBoolean("dyn_color", true)
    fun setDynamicColor(c: Context, v: Boolean) = sp(c).edit().putBoolean("dyn_color", v).apply()

    fun oledBlack(c: Context): Boolean = sp(c).getBoolean("oled_black", false)
    fun setOledBlack(c: Context, v: Boolean) = sp(c).edit().putBoolean("oled_black", v).apply()

    fun onboardingDone(c: Context): Boolean = sp(c).getBoolean("onboarding_done", false)
    fun setOnboardingDone(c: Context, v: Boolean) = sp(c).edit().putBoolean("onboarding_done", v).apply()

    fun exportTreeUri(c: Context): String? = sp(c).getString("export_tree_uri", null)
    fun setExportTreeUri(c: Context, v: String?) = sp(c).edit().putString("export_tree_uri", v).apply()

    fun recordedRows(c: Context): Long = sp(c).getLong("recorded_rows", 0L)
    fun setRecordedRows(c: Context, v: Long) = sp(c).edit().putLong("recorded_rows", v).apply()

    fun dualCell(c: Context): Boolean = sp(c).getBoolean("dual_cell", false)
    fun setDualCell(c: Context, v: Boolean) = sp(c).edit().putBoolean("dual_cell", v).apply()

    fun dualCellPrompted(c: Context): Boolean = sp(c).getBoolean("dual_cell_prompted", false)
    fun setDualCellPrompted(c: Context, v: Boolean) = sp(c).edit().putBoolean("dual_cell_prompted", v).apply()

    /** 0 = Material 3, 1 = MIUIX Lite, 2 = MIUIX Full (default in miuix flavor) */
    fun uiStyle(c: Context): Int = sp(c).getInt("ui_style", if (BuildConfig.HAS_MIUIX_LIB) 2 else 0)
    fun setUiStyle(c: Context, v: Int) = sp(c).edit().putInt("ui_style", v).apply()

    fun liquidGlassM3(c: Context): Boolean = sp(c).getBoolean("liquid_glass_m3", false)
    fun setLiquidGlassM3(c: Context, v: Boolean) = sp(c).edit().putBoolean("liquid_glass_m3", v).apply()

    fun liquidGlassMiuix(c: Context): Boolean = sp(c).getBoolean("liquid_glass_miuix", false)
    fun setLiquidGlassMiuix(c: Context, v: Boolean) = sp(c).edit().putBoolean("liquid_glass_miuix", v).apply()
}
