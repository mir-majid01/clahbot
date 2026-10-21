package com.clahbot.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.clahbot.data.SharedVisionBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WatchdogManager {

    private var appContext: Context? = null
    private val _thermalStatus = MutableStateFlow(ThermalLevel.NORMAL)
    val thermalStatus: StateFlow<ThermalLevel> = _thermalStatus.asStateFlow()

    var pauseReason: String = ""
        private set

    private var sessionStartMs = 0L
    private var workCycleStartMs = 0L
    private var isResting = false

    fun init(context: Context) {
        appContext = context.applicationContext
        sessionStartMs = System.currentTimeMillis()
        workCycleStartMs = sessionStartMs
    }

    fun canContinue(): Boolean {
        val ctx = appContext ?: return true

        val battery = getBatteryLevel(ctx)
        if (battery in 1..15) {
            pauseReason = "Low battery ($battery%)"
            return false
        }

        if (isDeviceOverheating(ctx)) {
            pauseReason = "Device overheating"
            _thermalStatus.value = ThermalLevel.SEVERE
            return false
        }

        if (isDozeMode(ctx)) {
            pauseReason = "Device in Doze mode"
            return false
        }

        _thermalStatus.value = ThermalLevel.NORMAL
        pauseReason = ""
        return true
    }

    fun shouldRest(workMinutes: Int, restMinutes: Int): Boolean {
        val elapsed = System.currentTimeMillis() - workCycleStartMs
        val workMs = workMinutes * 60_000L
        val restMs = restMinutes * 60_000L

        if (!isResting && elapsed >= workMs) {
            isResting = true
            workCycleStartMs = System.currentTimeMillis()
            SharedVisionBuffer.setStatus("Rest cycle ($restMinutes min)")
            return true
        }
        if (isResting && elapsed >= restMs) {
            isResting = false
            workCycleStartMs = System.currentTimeMillis()
            SharedVisionBuffer.setStatus("Resuming work cycle")
            return false
        }
        return isResting
    }

    fun getBatteryLevel(context: Context): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun isDeviceOverheating(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        } else false
    }

    private fun isDozeMode(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= 23) pm.isDeviceIdleMode else false
    }

    enum class ThermalLevel { NORMAL, MODERATE, SEVERE }
}
