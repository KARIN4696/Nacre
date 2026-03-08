package space.manus.nacre

import android.app.Application
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
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                // Use app-specific external dir — no permission needed, adb pull friendly
                val logDir = File(getExternalFilesDir(null), "crash-logs")
                logDir.mkdirs()
                val logFile = File(logDir, "crash_$timestamp.txt")
                logFile.writeText(
                    "Thread: ${thread.name}\n" +
                    "Time: $timestamp\n\n" +
                    sw.toString()
                )
            } catch (_: Exception) {
                // If we can't write the crash log, don't make things worse
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
