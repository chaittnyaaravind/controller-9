package dev.ninecontroller.android.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import dev.ninecontroller.protocol.HeldPose
import dev.ninecontroller.protocol.MotionSettings
import dev.ninecontroller.protocol.Pose
import dev.ninecontroller.protocol.PoseCapture
import dev.ninecontroller.protocol.Quat
import dev.ninecontroller.protocol.RelativeSteering
import dev.ninecontroller.protocol.SteeringCalibrationV2
import dev.ninecontroller.protocol.Vec3

/**
 * Phone orientation in its own (body) frame, on a dedicated high-priority thread. Registered only
 * while a steering screen is visible.
 *
 * Source, best first (docs/STEERING.md):
 *  1. TYPE_GAME_ROTATION_VECTOR — gyro + accelerometer fusion, no magnetometer: smooth, no magnetic
 *     disturbance; its heading drifts slowly, which only matters for turns about the vertical.
 *  2. TYPE_ROTATION_VECTOR — adds the magnetometer: no heading drift but can jump near magnets/metal.
 *  3. TYPE_GRAVITY / TYPE_ACCELEROMETER — gravity only: turns about the vertical are invisible.
 * The display rotation is never used: a portrait calibration screen and a landscape controller see
 * the same body-frame poses.
 */
class MotionSensor(context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null
    @Volatile var measuredHz = 0.0; private set
    @Volatile var source = "none"; private set
    val hasGyroscope: Boolean get() = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    private fun pick(): Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    fun availableSource(): String? = pick()?.let(::label)

    private fun label(s: Sensor) = when (s.type) {
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "game rotation vector"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation vector"
        Sensor.TYPE_GRAVITY -> "gravity"
        else -> "accelerometer"
    }

    /** `sink(pose, tSeconds)` runs on the sensor thread; t is the sensor's monotonic timestamp. */
    fun start(hz: Int, sink: (Pose, Double) -> Unit): Boolean {
        stop()
        val sensor = pick() ?: return false
        source = label(sensor)
        val t = HandlerThread("ninectl-motion", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        var lp: Vec3? = null
        var windowStart = 0L; var windowCount = 0
        val l = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(e: SensorEvent) {
                val pose: Pose? = when (e.sensor.type) {
                    Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> Quat.fromRotationVector(e.values)?.let { Pose.Orientation(it) }
                    // Accelerometers report the reaction to gravity (up); the pose wants gravity (down).
                    Sensor.TYPE_GRAVITY -> Vec3(-e.values[0].toDouble(), -e.values[1].toDouble(), -e.values[2].toDouble()).normalized()?.let { Pose.Gravity(it) }
                    else -> {
                        val raw = Vec3(-e.values[0].toDouble(), -e.values[1].toDouble(), -e.values[2].toDouble())
                        val f = lp?.let { it + (raw - it) * 0.2 } ?: raw // low-pass to approximate gravity
                        lp = f
                        f.normalized()?.let { Pose.Gravity(it) }
                    }
                }
                if (pose != null) sink(pose, e.timestamp / 1e9)
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

/**
 * Live relative steering on top of [MotionSensor]: steering, recenter capture and calibration changes
 * share one lock with the sensor thread, so a recenter blends instead of stepping.
 */
class SteeringRunner(private val sensor: MotionSensor) {
    private val lock = Any()
    private var steering: RelativeSteering? = null
    private var capture: PoseCapture? = null
    private var lastT = 0.0
    @Volatile var output = 0.0; private set
    /** Filtered angle from neutral (NaN until measurable). */
    @Volatile var angleDeg = Double.NaN; private set
    @Volatile var lastPose: Pose? = null; private set
    val source get() = sensor.source
    val hz get() = sensor.measuredHz
    val calibration get() = synchronized(lock) { steering?.calibration }

    fun start(hz: Int, cal: SteeringCalibrationV2?, motion: MotionSettings, onOutput: (Double) -> Unit): Boolean {
        synchronized(lock) { steering = RelativeSteering(cal, motion) }
        return sensor.start(hz) { pose, t ->
            var v = 0.0
            synchronized(lock) {
                lastT = t; lastPose = pose
                capture?.add(pose)
                steering?.let { s -> v = s.update(pose, t); angleDeg = s.angleDeg }
            }
            output = v
            onOutput(v)
        }
    }

    fun stop() { sensor.stop(); synchronized(lock) { capture = null } }
    fun beginCapture() = synchronized(lock) { capture = PoseCapture() }
    fun endCapture(): HeldPose? = synchronized(lock) { val c = capture; capture = null; c?.finish() }
    fun cancelCapture() = synchronized(lock) { capture = null }
    /** New reference without a step in the output (RelativeSteering blends the difference out). */
    fun recalibrate(cal: SteeringCalibrationV2?) = synchronized(lock) { steering?.recalibrate(cal, lastT) }
}
