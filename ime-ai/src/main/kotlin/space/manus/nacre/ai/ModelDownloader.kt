package space.manus.nacre.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Model download manager.
 *
 * Downloads Whisper and LLM model files to internal storage.
 * Provides progress updates and supports resumption.
 *
 * Models:
 * - Whisper base: ~142MB (whisper-base.bin)
 * - Gemma 3 1B INT4: ~600MB (gemma-3-1b-q4.gguf)
 */
class ModelDownloader(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    /**
     * Check if AI addon is purchased. Must be verified before downloading.
     * Uses SharedPreferences set by BillingManager in the app module.
     */
    fun isAiAddonPurchased(): Boolean {
        val prefs = context.getSharedPreferences("nacre_billing", Context.MODE_PRIVATE)
        return prefs.getBoolean("ai_addon_purchased", false)
    }

    data class DownloadProgress(
        val modelName: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val isComplete: Boolean = false,
        val error: String? = null,
    ) {
        val percent: Int
            get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
    }

    var onProgress: ((DownloadProgress) -> Unit)? = null

    /**
     * Get the models directory, creating it if needed.
     */
    fun getModelsDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Check which models are already downloaded.
     */
    fun getDownloadedModels(): Map<String, Boolean> {
        val dir = getModelsDir()
        return mapOf(
            "whisper" to File(dir, "whisper-base.bin").exists(),
            "llm" to File(dir, "gemma-3-1b-q4.gguf").exists(),
            "kenlm" to File(dir, "japanese-5gram.klm").exists(),
        )
    }

    /**
     * Download a model from URL.
     *
     * @param url Download URL
     * @param modelName Name for progress reporting
     * @param fileName Output file name
     * @param onComplete Called when download finishes
     */
    fun downloadModel(
        url: String,
        modelName: String,
        fileName: String,
        onComplete: (Boolean) -> Unit,
    ) {
        // SPEC: require purchase before model download
        if (!isAiAddonPurchased()) {
            Log.w(TAG, "AI addon not purchased, refusing download")
            onComplete(false)
            return
        }

        currentJob = scope.launch {
            val outFile = File(getModelsDir(), fileName)
            val tmpFile = File(getModelsDir(), "$fileName.tmp")

            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000

                // Support resume
                if (tmpFile.exists()) {
                    connection.setRequestProperty("Range", "bytes=${tmpFile.length()}-")
                }

                connection.connect()

                val totalBytes = connection.contentLengthLong + (if (tmpFile.exists()) tmpFile.length() else 0)
                var bytesDownloaded = if (tmpFile.exists()) tmpFile.length() else 0L

                val input = connection.inputStream
                val output = FileOutputStream(tmpFile, tmpFile.exists())

                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (!isActive) {
                        input.close()
                        output.close()
                        connection.disconnect()
                        return@launch
                    }

                    output.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead

                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(
                            DownloadProgress(modelName, bytesDownloaded, totalBytes),
                        )
                    }
                }

                output.close()
                input.close()
                connection.disconnect()

                // Rename tmp to final
                tmpFile.renameTo(outFile)

                withContext(Dispatchers.Main) {
                    onProgress?.invoke(
                        DownloadProgress(modelName, totalBytes, totalBytes, isComplete = true),
                    )
                    onComplete(true)
                }

                Log.i(TAG, "Model downloaded: $fileName (${totalBytes / 1024 / 1024}MB)")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: $modelName", e)
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(
                        DownloadProgress(modelName, 0, 0, error = e.message),
                    )
                    onComplete(false)
                }
            }
        }
    }

    fun cancelDownload() {
        currentJob?.cancel()
        currentJob = null
    }

    /**
     * Delete downloaded models to free space.
     */
    fun deleteModels() {
        val dir = getModelsDir()
        dir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "All models deleted")
    }

    /**
     * Get total size of downloaded models in bytes.
     */
    fun getModelsSize(): Long {
        val dir = getModelsDir()
        return dir.listFiles()?.sumOf { it.length() } ?: 0
    }

    fun destroy() {
        scope.cancel()
    }

    /**
     * Download the KenLM 5-gram Japanese language model.
     * Trained on Wikipedia (~561MB). Significantly improves conversion accuracy.
     */
    fun downloadKenLm(onComplete: (Boolean) -> Unit) {
        downloadModel(
            url = KENLM_URL,
            modelName = "KenLM 日本語5-gram",
            fileName = KENLM_FILENAME,
            onComplete = onComplete,
        )
    }

    /**
     * Get KenLM model file if it exists.
     */
    fun getKenLmModel(): File? {
        val file = File(getModelsDir(), KENLM_FILENAME)
        return if (file.exists()) file else null
    }

    companion object {
        private const val TAG = "ModelDownloader"
        const val KENLM_FILENAME = "japanese-5gram.klm"
        const val KENLM_URL = "https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models/japanese-5gram.klm"
    }
}
