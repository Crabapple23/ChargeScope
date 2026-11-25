package com.chargescope.app.util

/** Mirrors cs-core::bounds. Keep in sync with docs/CONTRACT.md. */
object NumberLimits {
    const val VOLTAGE_UV_MIN = 2_000_000L
    const val VOLTAGE_UV_MAX = 22_000_000L
    const val CURRENT_UA_ABS_MAX = 15_000_000L
    const val POWER_UW_MAX = 300_000_000L
    const val TEMP_DECIC_MIN = -400
    const val TEMP_DECIC_MAX = 800
}
