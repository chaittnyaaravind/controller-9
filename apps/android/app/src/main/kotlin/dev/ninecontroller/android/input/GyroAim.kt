package dev.ninecontroller.android.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Gyro aim: how fast the phone turns becomes right-stick deflection, the way DualSense and Switch Pro
 * players aim. Games read a stick as a turn *speed*, so rate → deflection matches what they expect.
 *
 * Yaw is measured about gravity ("player space"): turning left/right aims left/right however the
 * phone is tilted in the hands. Pitch is measured about the screen's horizontal axis ("local space").
 */
object GyroAimMath {
    /** Turn speed (rad/s) that gives full stick at sensitivity 1. */
    const val FULL_RATE = 3.0
    /** Below this (rad/s) the phone counts as still: hides sensor noise and hand tremor. */
    const val DEADZONE = 0.035

    /** Screen right and up in device coordinates for a display rotation (Surface.ROTATION_*). */
    fun viewAxes(rotation: Int): Pair<DoubleArray, DoubleArray> = when (rotation) {
        Surface.ROTATION_90 -> doubleArrayOf(0.0, -1.0, 0.0) to doubleArrayOf(1.0, 0.0, 0.0)
        Surface.ROTATION_270 -> doubleArrayOf(0.0, 1.0, 0.0) to doubleArrayOf(-1.0, 0.0, 0.0)
        Surface.ROTATION_180 -> doubleArrayOf(-1.0, 0.0, 0.0) to doubleArrayOf(0.0, -1.0, 0.0)
        else -> doubleArrayOf(1.0, 0.0, 0.0) to doubleArrayOf(0.0, 1.0, 0.0)
    }

    /**
     * Stick (x right, y up, each −1…1) for angular velocity [w] (device rad/s), gravity-up [up]
     * (device coordinates, any length) and the screen's right axis [right].
     */
    fun stick(w: DoubleArray, up: DoubleArray, right: DoubleArray, sensitivity: Double, invertY: Boolean): Pair<Double, Double> {
        val n = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]).takeIf { it > 1e-6 } ?: return 0.0 to 0.0
        // A turn to the left is positive rotation about "up"; aiming left is negative x.
        val yaw = -(w[0] * up[0] + w[1] * up[1] + w[2] * up[2]) / n
        // Positive rotation about screen-right tips the far edge up: aiming up.
        val pitch = w[0] * right[0] + w[1] * right[1] + w[2] * right[2]
        fun shape(r: Double): Double {
            if (abs(r) < DEADZONE) return 0.0
            val v = (abs(r) - DEADZONE) * sensitivity / FULL_RATE
            return Math.copySign(v.coerceAtMost(1.0), r)
        }
        return shape(yaw) to shape(if (invertY) -pitch else pitch)
    }
}

/** Reads the gyroscope (and gravity, for player-space yaw) on its own thread. */
class GyroAim(context: Context) {
    private companion object { const val AIM_PERIOD_US = 5_000 }
    private val sm = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private val listeners = ArrayList<SensorEventListener>()
    @Volatile private var up = doubleArrayOf(0.0, 0.0, 1.0)

    val available: Boolean get() = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    /** [rotation] is the display rotation; [out] gets stick x, y (−1…1) at the sensor rate. */
    fun start(rotation: Int, sensitivity: Double, invertY: Boolean, out: (Double, Double) -> Unit): Boolean {
        stop()
        val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val (right, _) = GyroAimMath.viewAxes(rotation)
        val t = HandlerThread("c9-gyro-aim").apply { start() }; thread = t
        val h = Handler(t.looper)
        // One listener per sensor: some phones drop a second sensor registered on a shared listener.
        val gyroL = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(e: SensorEvent) {
                val w = doubleArrayOf(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                val (x, y) = GyroAimMath.stick(w, up, right, sensitivity, invertY)
                out(x, y)
            }
        }
        val upL = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(e: SensorEvent) { up = doubleArrayOf(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble()) }
        }
        listeners += gyroL; listeners += upL
        // 200 Hz: the fastest rate Android allows without HIGH_SAMPLING_RATE_SENSORS (asking for
        // SENSOR_DELAY_FASTEST without it throws). A sensor refusal turns aim off, never crashes.
        runCatching { gravity?.let { sm.registerListener(upL, it, SensorManager.SENSOR_DELAY_GAME, h) } }
        return runCatching { sm.registerListener(gyroL, gyro, AIM_PERIOD_US, h) }.getOrDefault(false).also { if (!it) stop() }
    }

    fun stop() {
        listeners.forEach { sm?.unregisterListener(it) }; listeners.clear()
        thread?.quitSafely(); thread = null
    }
}
