package space.manus.nacre

import android.app.Application
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NacreApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val content = "Thread: ${thread.name}\nTime: $timestamp\n\n${sw}"

            // Try multiple locations — at least one should be accessible
            val candidates = listOfNotNull(
                // 1. App-internal files dir (always writable, accessible via run-as on debug)
                try { File(filesDir, "crash-logs") } catch (_: Exception) { null },
                // 2. App-specific external dir (accessible via Android/data/...)
                try { getExternalFilesDir(null)?.let { File(it, "crash-logs") } } catch (_: Exception) { null },
                // 3. Public Documents dir (accessible from Termux ~/storage/shared/Documents/)
                try { File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "nacre-crash"
                ) } catch (_: Exception) { null },
            )

            for (dir in candidates) {
                try {
                    dir.mkdirs()
                    File(dir, "crash_$timestamp.txt").writeText(content)
                } catch (_: Exception) {
                    // Try next location
                }
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
