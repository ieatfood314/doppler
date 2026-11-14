package ca.heeney.doppler

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import androidx.media3.session.CacheBitmapLoader
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
        session = MediaSession.Builder(this, Gate(engine.player, engine)).setSessionActivity(open)
            .setBitmapLoader(CacheBitmapLoader(Covers(this, engine))).build()
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

/** Covers for the notification and lock screen: the phone's own copy's picture first, then the hub's,
 * which is only fetched on Wi-Fi. */
@OptIn(UnstableApi::class)
private class Covers(private val context: Context, private val engine: Engine) : BitmapLoader {
    private val executor = DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get()
    private val hub = DataSourceBitmapLoader(executor,
        DefaultDataSource.Factory(context, WifiOnlySource.Factory(DefaultHttpDataSource.Factory(), wait = false)))

    override fun supportsMimeType(mimeType: String) = hub.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = hub.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val file = engine.localForArt(uri) ?: return hub.loadBitmap(uri)
        val own = executor.submit<Bitmap?> {
            val picture = runCatching {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(context, file); r.embeddedPicture } finally { r.release() }
            }.getOrNull()
            picture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        return Futures.transformAsync(own, { b -> if (b != null) Futures.immediateFuture(b) else hub.loadBitmap(uri) },
            MoreExecutors.directExecutor())
    }
}
