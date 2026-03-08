package space.manus.nacre.ime.foldable

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.WindowManager

/**
 * Detects foldable device state: hinge angle, sub-display, and table-top mode.
 * Uses standard Android APIs with graceful fallbacks for non-foldable devices.
 */
class FoldableDetector(private val context: Context) : SensorEventListener {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val hingeSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    var hingeAngle: Float = 0f
        private set

    /**
     * Returns the current window width in dp.
     * Uses WindowManager.getCurrentWindowMetrics (API 30+) with a fallback
     * to the legacy display metrics path for API 26-29.
     */
    fun getScreenWidthDp(): Float {
        val density = context.resources.displayMetrics.density
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() / density
        } else {
            @Suppress("DEPRECATION")
            val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getMetrics(metrics)
            metrics.widthPixels / density
        }
    }

    /**
     * Checks whether the device declares hinge angle sensor support,
     * which is the primary indicator of a foldable device.
     */
    fun isFoldableDevice(): Boolean {
        return context.packageManager
            .hasSystemFeature("android.hardware.sensor.hinge_angle")
    }

    /**
     * Returns true when running on the smaller sub-display of a foldable
     * (e.g. Galaxy Z Flip cover screen). Defined as foldable + width < 500dp.
     */
    fun isSubDisplay(): Boolean {
        return isFoldableDevice() && getScreenWidthDp() < 500f
    }

    /**
     * Begins listening to hinge angle sensor updates.
     * No-op if the device does not have a hinge angle sensor.
     */
    fun startHingeAngleListening() {
        hingeSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
            )
        }
    }

    /**
     * Stops listening to hinge angle sensor updates.
     */
    fun stopHingeAngleListening() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Table-top mode: the device is partially folded and resting on a surface,
     * with the hinge angle between 60 and 160 degrees.
     */
    fun isTableTopMode(): Boolean {
        return hingeAngle in 60f..160f
    }

    // --- SensorEventListener ---

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HINGE_ANGLE) {
            hingeAngle = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No action needed
    }
}
