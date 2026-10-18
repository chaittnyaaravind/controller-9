package dev.ninecontroller.android.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface

/**
 * Gravity in *screen* coordinates (x right, y up), sampled from the fused game rotation vector
 * (gyro + accelerometer, no magnetometer) on a dedicated high-priority thread.
 * Registered only while the controller screen is visible and motion steering is on.
 */
class MotionSensor(context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null
    @Volatile var measuredHz = 0.0; private set
    @Volatile var source = "none"; private set

    fun availableSource(): String? = when {
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null -> "game rotation vector"
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY) != null -> "gravity"
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null -> "accelerometer"
        else -> null
    }

    /** `rotation` returns the current Surface.ROTATION_*; `sink(gx, gy, gz, tSeconds)` runs on the sensor thread. */
    fun start(hz: Int, rotation: () -> Int, sink: (Double, Double, Double, Double) -> Unit): Boolean {
        stop()
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        source = when (sensor.type) { Sensor.TYPE_GAME_ROTATION_VECTOR -> "game rotation vector"; Sensor.TYPE_GRAVITY -> "gravity"; else -> "accelerometer" }
        val t = HandlerThread("ninectl-motion", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        val r = FloatArray(9)
        var lp = DoubleArray(3)
        var windowStart = 0L; var windowCount = 0
        val l = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(e: SensorEvent) {
                // Down-pointing gravity in device coordinates.
                val g = when (e.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(r, e.values)
                        doubleArrayOf(-r[6] * 9.81, -r[7] * 9.81, -r[8] * 9.81)
                    }
                    Sensor.TYPE_GRAVITY -> doubleArrayOf(-e.values[0].toDouble(), -e.values[1].toDouble(), -e.values[2].toDouble())
                    else -> { // low-pass the accelerometer to approximate gravity
                        lp = DoubleArray(3) { i -> lp[i] + 0.2 * (-e.values[i] - lp[i]) }; lp
                    }
                }
                val (sx, sy) = when (rotation()) {
                    Surface.ROTATION_90 -> -g[1] to g[0]
                    Surface.ROTATION_180 -> -g[0] to -g[1]
                    Surface.ROTATION_270 -> g[1] to -g[0]
                    else -> g[0] to g[1]
                }
                sink(sx, sy, g[2], e.timestamp / 1e9)
                windowCount++
                if (windowStart == 0L) windowStart = e.timestamp
                if (e.timestamp - windowStart >= 1_000_000_000L) {
                    measuredHz = windowCount * 1e9 / (e.timestamp - windowStart); windowStart = e.timestamp; windowCount = 0
                }
            }
        }
        listener = l
        return sm.registerListener(l, sensor, 1_000_000 / hz.coerceIn(50, 400), Handler(t.looper))
    }

    fun stop() {
        listener?.let { sm.unregisterListener(it) }
        listener = null
        thread?.quitSafely()
        thread = null
        measuredHz = 0.0
    }
}
