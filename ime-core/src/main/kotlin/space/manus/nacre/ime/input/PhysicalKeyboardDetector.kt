package space.manus.nacre.ime.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice

/**
 * Detects whether a physical (hardware) keyboard is connected to the device.
 * Iterates over all input devices and checks for non-virtual keyboard sources.
 */
class PhysicalKeyboardDetector(private val context: Context) :
    InputManager.InputDeviceListener {

    private val inputManager: InputManager =
        context.getSystemService(Context.INPUT_SERVICE) as InputManager

    /**
     * Callback invoked when the physical keyboard connection state changes.
     * The Boolean parameter is true when a physical keyboard is connected.
     */
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    /**
     * Returns true if at least one non-virtual physical keyboard is connected.
     * Checks all registered InputDevices for SOURCE_KEYBOARD on non-virtual devices.
     */
    fun isPhysicalKeyboardConnected(): Boolean {
        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.isVirtual) continue
            val sources = device.sources
            if (sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD) {
                // Ensure it has alphabetic keys (filters out volume/power button devices)
                val hasAlphaKeys = device.keyCharacterMap != null &&
                    device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                if (hasAlphaKeys) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Starts listening for input device connection/disconnection events.
     */
    fun startListening() {
        inputManager.registerInputDeviceListener(this, null)
    }

    /**
     * Stops listening for input device events.
     */
    fun stopListening() {
        inputManager.unregisterInputDeviceListener(this)
    }

    // --- InputDeviceListener ---

    override fun onInputDeviceAdded(deviceId: Int) {
        onConnectionChanged?.invoke(isPhysicalKeyboardConnected())
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        onConnectionChanged?.invoke(isPhysicalKeyboardConnected())
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        onConnectionChanged?.invoke(isPhysicalKeyboardConnected())
    }
}
