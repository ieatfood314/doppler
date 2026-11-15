package ca.heeney.doppler

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay

/** The widget's buttons land here: an invisible screen that plays (or pauses) and goes away,
 * staying only long enough to ask before anything comes out of the phone's own speaker.
 *
 * It also stays (invisible, and letting taps through to the home screen) until the music has actually
 * started. A cold start takes a few seconds (reading the phone's files, asking the hub where the playlist
 * was), and while this screen is up the app counts as in use, which is what lets the playback service go
 * foreground. Gone too early, the music could start without it and die soon after. */
class ConfirmActivity : ComponentActivity() {
    companion object {
        const val SLOT = "ca.heeney.doppler.SLOT"
        const val ASK = "ca.heeney.doppler.ASK"
        const val LONGEST_MS = 10_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = Engine.get(this)
        handle(intent, engine)
        setContent {
            val state by engine.state.collectAsState()
            LaunchedEffect(state.askSpeaker, state.starting) {
                if (state.askSpeaker == null && !state.starting) finish()
            }
            LaunchedEffect(state.askSpeaker) {
                // Nothing to show while waiting, so taps go to whatever is underneath. Android only lets them
                // through a window of another app that is fully transparent, so the window goes to alpha 0 too.
                val waiting = state.askSpeaker == null
                if (waiting) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                window.attributes = window.attributes.apply { alpha = if (waiting) 0f else 1f }
            }
            LaunchedEffect(Unit) {
                delay(LONGEST_MS)
                if (engine.state.value.askSpeaker == null) finish()
            }
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
