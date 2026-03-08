package space.manus.nacre.ime.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import space.manus.nacre.config.DefaultLayouts
import space.manus.nacre.config.KeyboardLayout

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

    fun toggleFn() {
        currentLayer = when (currentLayer) {
            Layer.Base -> Layer.Fn1
            Layer.Fn1 -> Layer.Base
            Layer.Fn2 -> Layer.Base
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

    fun currentLayout(): KeyboardLayout = when (currentLayer) {
        Layer.Base -> DefaultLayouts.qwertyBase
        Layer.Fn1 -> DefaultLayouts.fnLayer1
        Layer.Fn2 -> DefaultLayouts.fnLayer2
    }
}
