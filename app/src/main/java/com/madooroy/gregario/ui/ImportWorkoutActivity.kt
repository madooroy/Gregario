package com.madooroy.gregario.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.madooroy.gregario.Gregario
import com.madooroy.gregario.workout.WorkoutFiles
import timber.log.Timber
import kotlin.concurrent.thread

/**
 * "Open with" / "Share to" Gregario for a workout file (.zwo, .mrc, .erg) from an email, Drive or a download.
 * No screen of its own: it reads the file, hands it to Gregario ("Your workouts" in the panel) and says so.
 */
class ImportWorkoutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gregario = Gregario.get(application)
        // A malformed extra from another app must not crash the app (review I2); only content: addresses (not file:).
        val uri: Uri? = runCatching {
            when (intent?.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                else -> null
            }
        }.getOrNull()?.takeIf { it.scheme == "content" }
        val sharedText = runCatching { if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null }
            .getOrNull()?.takeIf { it.length <= WorkoutFiles.MAX_CHARS }
        val resolver = contentResolver
        thread(name = "gregario-import") {
            val msg = runCatching {
                when {
                    uri != null -> {
                        val name = displayName(uri) ?: uri.lastPathSegment ?: "workout"
                        val text = resolver.openInputStream(uri)!!.use { com.madooroy.gregario.workout.readWorkoutText(it) }
                        gregario.importWorkout(name, text)
                    }
                    sharedText != null -> gregario.importWorkout("shared.zwo", sharedText)
                    else -> "Nothing to open"
                }
            }.getOrElse { e -> Timber.w(e, "Gregario import failed"); "Gregario couldn't open that file: ${e.message}" }
            runOnUiThread { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show(); finish() }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
