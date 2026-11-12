package ca.heeney.doppler

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** The widget's buttons land here: an invisible screen that plays (or pauses) and goes away,
 * staying only long enough to ask before anything comes out of the phone's own speaker. */
class ConfirmActivity : ComponentActivity() {
    companion object {
        const val SLOT = "ca.heeney.doppler.SLOT"
        const val ASK = "ca.heeney.doppler.ASK"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = Engine.get(this)
        handle(intent, engine)
        setContent {
            val state by engine.state.collectAsState()
            LaunchedEffect(state.askSpeaker) { if (state.askSpeaker == null) finish() }
            state.askSpeaker?.let { pending ->
                SpeakerQuestion(pending, engine.state.value) { yes -> engine.answerSpeaker(yes) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent, Engine.get(this))
    }

    private fun handle(intent: Intent?, engine: Engine) {
        when (intent?.action) {
            SLOT -> engine.slot(intent.getStringExtra("slot") ?: "1")
            ASK -> if (engine.state.value.askSpeaker == null) engine.resume()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
