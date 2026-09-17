package com.example.airpodsbattery

import android.content.Context

/**
 * 最近一次读数。前台服务和桌面小组件是两个进程级组件，小组件没法直接读服务里的内存状态，
 * 所以把结果落到 SharedPreferences，两边都从这里取。
 */
object BatteryStore {
    private const val PREFS = "airpods_battery"
    private const val KEY_LEFT = "left"
    private const val KEY_RIGHT = "right"
    private const val KEY_CASE = "case"
    private const val KEY_LEFT_CHARGING = "left_charging"
    private const val KEY_RIGHT_CHARGING = "right_charging"
    private const val KEY_CASE_CHARGING = "case_charging"
    private const val KEY_LAST_SEEN = "last_seen"
    private const val KEY_MODEL = "model"
    private const val KEY_RAW = "raw"
    private const val KEY_ADDRESS = "address"
    private const val KEY_RSSI = "rssi"

    private const val NONE = -1

    fun save(context: Context, state: AirPodsState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LEFT, state.left ?: NONE)
            .putInt(KEY_RIGHT, state.right ?: NONE)
            .putInt(KEY_CASE, state.case ?: NONE)
            .putBoolean(KEY_LEFT_CHARGING, state.leftCharging)
            .putBoolean(KEY_RIGHT_CHARGING, state.rightCharging)
            .putBoolean(KEY_CASE_CHARGING, state.caseCharging)
            .putLong(KEY_LAST_SEEN, state.lastSeenAt ?: 0L)
            .putString(KEY_MODEL, state.modelName)
            .putString(KEY_RAW, state.lastRawHex)
            .putString(KEY_ADDRESS, state.lastAddress)
            .putInt(KEY_RSSI, state.lastRssi ?: 0)
            .apply()
    }

    fun load(context: Context): AirPodsState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun level(key: String): Int? = prefs.getInt(key, NONE).takeIf { it != NONE }
        return AirPodsState(
            modelName = prefs.getString(KEY_MODEL, null),
            left = level(KEY_LEFT),
            right = level(KEY_RIGHT),
            case = level(KEY_CASE),
            leftCharging = prefs.getBoolean(KEY_LEFT_CHARGING, false),
            rightCharging = prefs.getBoolean(KEY_RIGHT_CHARGING, false),
            caseCharging = prefs.getBoolean(KEY_CASE_CHARGING, false),
            lastSeenAt = prefs.getLong(KEY_LAST_SEEN, 0L).takeIf { it > 0 },
            lastRawHex = prefs.getString(KEY_RAW, null),
            lastAddress = prefs.getString(KEY_ADDRESS, null),
            lastRssi = prefs.getInt(KEY_RSSI, 0).takeIf { it != 0 },
        )
    }
}
