package space.manus.nacre.ime.feedback

import android.content.Context
import space.manus.nacre.config.KeyAction

class FeedbackManager(context: Context) {

    private val haptic = HapticManager(context)
    private val sound = SoundManager(context)

    val hapticManager: HapticManager get() = haptic
    val soundManager: SoundManager get() = sound

    fun onKeyPress(keyAction: KeyAction) {
        haptic.onKeyTap()

        val keyType = when (keyAction) {
            is KeyAction.Space -> SoundManager.KeyType.SPACE
            is KeyAction.Backspace -> SoundManager.KeyType.BACKSPACE
            is KeyAction.Enter -> SoundManager.KeyType.ENTER
            else -> SoundManager.KeyType.NORMAL
        }
        sound.onKeyTap(keyType)
    }

    fun onSwipe() {
        haptic.onKeyTap()
        sound.onKeyTap(SoundManager.KeyType.NORMAL)
    }

    fun onLongPress() {
        haptic.onLongPress()
    }

    fun onTrackballStep() {
        haptic.onTrackballStep()
    }

    fun onLayerChange() {
        haptic.onLayerChange()
    }

    fun onError() {
        haptic.onError()
    }

    fun release() {
        sound.release()
    }
}
