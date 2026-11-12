package ca.heeney.doppler

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        /** Open on a playlist's page (extra "playlist"), as the widget's playing number does. */
        const val OPEN = "ca.heeney.doppler.OPEN"
    }

    private lateinit var engine: Engine
    private val opening = mutableStateOf<String?>(null)

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        lifecycleScope.launch { engine.refreshLocal() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        engine = Engine.get(this)
        val wanted = buildList {
            if (!LocalFiles.allowed(this@MainActivity)) add(LocalFiles.permission)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val prefs = getSharedPreferences("doppler", MODE_PRIVATE)
        if (wanted.isNotEmpty() && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            ask.launch(wanted.toTypedArray())
        }
        if (savedInstanceState == null) take(intent)
        setContent {
            App(engine, askFiles = { ask.launch(arrayOf(LocalFiles.permission)) },
                opening = opening.value, opened = { opening.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        // Reopening from Recents hands back the widget's old intent; that should land on home as usual.
        if (intent?.action == OPEN && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0)
            opening.value = intent.getStringExtra("playlist")
    }

    override fun onStart() {
        super.onStart()
        engine.uiShown()
    }

    override fun onStop() {
        engine.uiHidden()
        super.onStop()
    }
}
