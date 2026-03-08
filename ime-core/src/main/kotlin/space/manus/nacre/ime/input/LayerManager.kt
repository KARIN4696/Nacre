package space.manus.nacre.ime.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import space.manus.nacre.config.DefaultLayouts
import space.manus.nacre.config.KeyboardLayout
import space.manus.nacre.config.PresetProvider

enum class Layer {
    Base,
    Fn1,
    Fn2,
}

class LayerManager {
    var currentLayer by mutableStateOf(Layer.Base)
        private set

    var isShifted by mutableStateOf(false)
        private set

    var isJapanese by mutableStateOf(false)
        private set

    var isCommandPaletteRequested by mutableStateOf(false)

    var activePreset: PresetProvider.PresetType = PresetProvider.PresetType.Default

    // Fn hold (temporary layer switch) state
    private var fnHeld = false

    fun toggleFn() {
        currentLayer = when (currentLayer) {
            Layer.Base -> Layer.Fn1
            Layer.Fn1 -> Layer.Base
            Layer.Fn2 -> Layer.Base
        }
    }

    /** Called when Fn key is pressed down (hold = temporary switch) */
    fun fnDown() {
        if (currentLayer == Layer.Base) {
            fnHeld = true
            currentLayer = Layer.Fn1
        }
    }

    /** Called when Fn key is released. If held, return to base. */
    fun fnUp(wasTap: Boolean) {
        if (fnHeld) {
            fnHeld = false
            if (wasTap) {
                // Short tap: toggle (stay on Fn1)
                // Already on Fn1 from fnDown, keep it
            } else {
                // Was held: return to base
                currentLayer = Layer.Base
            }
        }
    }

    fun toggleFn2() {
        currentLayer = when (currentLayer) {
            Layer.Fn1 -> Layer.Fn2
            Layer.Fn2 -> Layer.Fn1
            Layer.Base -> Layer.Fn2
        }
    }

    fun resetToBase() {
        currentLayer = Layer.Base
    }

    fun toggleShift() {
        isShifted = !isShifted
    }

    fun toggleJapanese() {
        isJapanese = !isJapanese
    }

    fun requestCommandPalette() {
        isCommandPaletteRequested = true
    }

    fun currentLayout(): KeyboardLayout = when (currentLayer) {
        Layer.Base -> PresetProvider.getLayout(activePreset)
        Layer.Fn1 -> DefaultLayouts.fnLayer1
        Layer.Fn2 -> DefaultLayouts.fnLayer2
    }
}
