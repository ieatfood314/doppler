package ca.heeney.doppler

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var engine: Engine

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
        setContent { App(engine, askFiles = { ask.launch(arrayOf(LocalFiles.permission)) }) }
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
