package space.manus.nacre.ime.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService

class SoundManager(private val context: Context) {

    enum class KeyType {
        NORMAL,
        SPACE,
        BACKSPACE,
        ENTER,
    }

    enum class Profile(val dirName: String) {
        THOCK("thock"),
        CLICKY("clicky"),
        SILENT("silent"),
        TYPEWRITER("typewriter"),
    }

    companion object {
        private const val TAG = "NacreSoundManager"
        private const val PREFS_NAME = "nacre_sound"
        private const val KEY_PROFILE = "profile"
        private const val KEY_VOLUME = "volume"
        private const val MAX_STREAMS = 6
        private const val DEFAULT_VOLUME = 70
    }

    private val powerManager: PowerManager? = context.getSystemService()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var soundPool: SoundPool? = null

    // Maps profile -> keyType -> soundId
    private val loadedSounds = mutableMapOf<Profile, MutableMap<KeyType, Int>>()

    var profile: Profile = loadProfile()
        private set

    /** Volume from 0 to 100. */
    var volume: Int = loadVolume()
        private set

    private val normalizedVolume: Float
        get() = volume / 100f

    private val isAvailable: Boolean
        get() = volume > 0 && !isBatterySaverActive()

    init {
        initSoundPool()
        preloadSounds(profile)
    }

    private fun initSoundPool() {
        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(audioAttributes)
            .build()
    }

    fun onKeyTap(keyType: KeyType = KeyType.NORMAL) {
        if (!isAvailable) return
        val pool = soundPool ?: return

        val soundId = loadedSounds[profile]?.get(keyType)
            ?: loadedSounds[profile]?.get(KeyType.NORMAL)
            ?: return

        pool.play(soundId, normalizedVolume, normalizedVolume, 1, 0, 1f)
    }

    fun setProfile(newProfile: Profile) {
        if (profile == newProfile) return
        profile = newProfile
        prefs.edit().putString(KEY_PROFILE, newProfile.name).apply()
        preloadSounds(newProfile)
    }

    fun setVolume(newVolume: Int) {
        volume = newVolume.coerceIn(0, 100)
        prefs.edit().putInt(KEY_VOLUME, volume).apply()
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        loadedSounds.clear()
    }

    private fun preloadSounds(targetProfile: Profile) {
        val pool = soundPool ?: return
        val profileSounds = loadedSounds.getOrPut(targetProfile) { mutableMapOf() }

        // Skip if already loaded
        if (profileSounds.isNotEmpty()) return

        for (keyType in KeyType.entries) {
            val fileName = "sounds/${targetProfile.dirName}/${keyType.name.lowercase()}.ogg"
            try {
                val afd = context.assets.openFd(fileName)
                val soundId = pool.load(afd, 1)
                profileSounds[keyType] = soundId
                afd.close()
            } catch (e: Exception) {
                // Sound file not found; gracefully degrade
                Log.d(TAG, "Sound asset not found: $fileName (${e.message})")
            }
        }
    }

    private fun isBatterySaverActive(): Boolean {
        return powerManager?.isPowerSaveMode == true
    }

    private fun loadProfile(): Profile {
        val name = prefs.getString(KEY_PROFILE, Profile.THOCK.name)
        return try {
            Profile.valueOf(name ?: Profile.THOCK.name)
        } catch (_: IllegalArgumentException) {
            Profile.THOCK
        }
    }

    private fun loadVolume(): Int {
        return prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100)
    }
}
