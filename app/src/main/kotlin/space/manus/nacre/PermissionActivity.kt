package space.manus.nacre

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Transparent Activity solely for requesting runtime permissions.
 * IME services cannot show permission dialogs, so we launch this
 * invisible Activity to request RECORD_AUDIO when needed.
 *
 * Usage: PermissionActivity.requestMicPermission(context)
 */
class PermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permission = intent.getStringExtra(EXTRA_PERMISSION) ?: Manifest.permission.RECORD_AUDIO

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            setResult(RESULT_OK)
            finish()
            return
        }

        ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            setResult(if (granted) RESULT_OK else RESULT_CANCELED)
        }
        finish()
    }

    override fun finish() {
        super.finish()
        // No animation for transparent activity
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQUEST_CODE = 1001
        private const val EXTRA_PERMISSION = "permission"

        fun requestMicPermission(context: Context) {
            val intent = Intent(context, PermissionActivity::class.java).apply {
                putExtra(EXTRA_PERMISSION, Manifest.permission.RECORD_AUDIO)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
