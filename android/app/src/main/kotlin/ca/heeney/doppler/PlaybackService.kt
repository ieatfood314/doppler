package ca.heeney.doppler

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Keeps the music going with the app closed, and gives the notification, lock screen,
 * headset buttons and Bluetooth controls something to talk to. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = Engine.get(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, Gate(engine.player, engine)).setSessionActivity(open).build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) })
        // The app drives the player directly rather than through a MediaController, so nothing would ever
        // ask for this session. Media3 only posts the notification and goes foreground (which is what keeps
        // the music alive once the app is swiped away) for sessions it has been handed.
        session?.let { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away leaves the music playing; with nothing playing there's no reason to stay.
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}

/** What outside controls see. Play goes through the same "out loud?" check as the screen,
 * and next at the end of a run reshuffles instead of stopping. */
@OptIn(UnstableApi::class)
private class Gate(player: Player, private val engine: Engine) : ForwardingPlayer(player) {
    override fun play() {
        when {
            !engine.gate() -> engine.askFromOutside()
            wrappedPlayer.mediaItemCount == 0 -> engine.resume()
            else -> super.play()
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else super.setPlayWhenReady(false)
    override fun seekToNext() = engine.next()
    override fun seekToNextMediaItem() = engine.next()
    override fun seekToPrevious() = engine.previous()
    override fun seekToPreviousMediaItem() = engine.previous()

    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()

    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
}
