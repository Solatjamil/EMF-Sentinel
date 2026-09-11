package com.example.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * REAL EMF source: wraps the phone's magnetometer and reports the total magnetic
 * flux density in micro-Tesla (µT), smoothed with a low-pass exponential filter.
 *
 * Honest behavior: if the device has no magnetometer, [hasMagnetometer] is false and
 * the UI must label its readings as simulated — never silently fake hardware data.
 */
class EmfSensorManager(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val hasMagnetometer: Boolean
        get() = magnetometer != null

    private var listener: SensorEventListener? = null
    private var smoothed: Float? = null

    /** Starts streaming smoothed µT readings; the callback fires on the sensor thread. */
    fun start(onReading: (Float) -> Unit) {
        val sensor = magnetometer ?: return
        if (listener != null) return // already running
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z) // total field µT
                val alpha = 0.12f // low-pass: smooths handset jitter while staying responsive
                val prev = smoothed
                val next = if (prev == null) magnitude else prev + alpha * (magnitude - prev)
                smoothed = next
                onReading(next)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = l
        sensorManager.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
        smoothed = null
    }
}
