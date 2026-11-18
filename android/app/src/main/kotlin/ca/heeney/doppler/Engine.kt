package ca.heeney.doppler

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** A play that is waiting for the "out loud?" answer. */
data class PendingPlay(val playlist: String, val track: String?, val fresh: Boolean)

data class Elsewhere(val device: String, val playlist: String, val name: String, val playing: Boolean, val track: Track?)

/** `kind` is folder, custom, artist or album; `by` is who an album is by. */
data class PlaylistView(
    val id: String, val name: String, val path: String, val kind: String, val count: Int, val slot: String,
    val mode: String, val lastTrack: Track?, val lastDevice: String?, val lastPlaying: Boolean, val by: String = "",
    /** When anyone last played it (hub seconds), 0 if never. */
    val updated: Double = 0.0,
) {
    val custom get() = kind == "custom"
}

data class UiState(
    val ready: Boolean = false,
    val playing: Boolean = false,
    val playlist: String? = null,
    val playlistName: String = "",
    val track: Track? = null,
    val index: Int = 0,
    val count: Int = 0,
    val slots: Map<String, String> = emptyMap(),
    val playlists: List<PlaylistView> = emptyList(),
    val elsewhere: Elsewhere? = null,
    val hubOnline: Boolean = false,
    val hubUrl: String = "",
    val device: String = "",
    val message: String = "",
    val askSpeaker: PendingPlay? = null,
    val output: String? = null,
    val onSpeaker: Boolean = false,
    val localCount: Int = 0,
    val libraryCount: Int = 0,
    val onWifi: Boolean = true,
    val mobileUsed: Long = 0,
    val customs: List<CustomPlaylist> = emptyList(),
    val playsRev: Int = 0,
    /** A press of play is on its way (reading the phone's files, asking the hub where the playlist was). */
    val starting: Boolean = false,
    /** ...and for which playlist. */
    val pending: String? = null,
)

/** Everything the phone does with music. One per process, living on the main thread;
 * network calls go to Dispatchers.IO and come back. */
@SuppressLint("StaticFieldLeak")
class Engine private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: Engine? = null
        fun get(context: Context): Engine = instance ?: synchronized(this) {
            instance ?: Engine(context.applicationContext).also { instance = it; it.start() }
        }

        /** The engine if this process already has one, without starting it. */
        fun peek(): Engine? = instance

        const val HEARTBEAT_MS = 15_000L
        const val HEARTBEAT_MOBILE_MS = 120_000L   // over mobile data, a word every two minutes is plenty
        const val SPEAKER_GRACE_MS = 10 * 60_000L
        const val LOCAL_WAIT_MS = 3_000L
        const val MESSAGE_MS = 12_000L
        const val RESUME_ON_WIFI_MS = 8 * 60_000L   // inside the ten minutes Media3 stays foreground after a stop
        const val CHANNEL = "speaker"
        const val TAG = "doppler"   // Log.i: Nothing OS drops Log.d from apps
    }

    val store = Store(context)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val rng = Random(System.nanoTime())

    private var hub = Hub(store.hubUrl)
    private var libraryEtag = ""
    var library: Map<String, Track> = emptyMap(); private set
    private var hubState: HubState? = null
    private val sessions: MutableMap<String, Session> = store.loadSessions()
    private val outbox: MutableMap<String, Pair<Boolean, Long>> = store.loadOutbox()
    private var local: Map<String, Uri> = emptyMap()
    private var scan: Deferred<Unit>? = null
    private var scanned = false     // the media index has been read at least once in this process

    private var current: String? = store.current
    private var order: List<String> = emptyList()
    private var playing = false
    private var claimedAt = 0.0
    private var hubOffset = 0.0
    private var hubOnline = false
    private var message = ""
    private var messageJob: Job? = null
    private var failures = 0            // tracks in a row that wouldn't play
    private var retried: String? = null // the track that has had its second chance
    private var starting: Job? = null
    private var pendingPlaylist: String? = null
    private var resumeOnWifiUntil = 0L  // stopped for want of Wi-Fi: carry on if it's back by then
    private var askSpeaker: PendingPlay? = null
    private var speakerAllowedUntil = 0L
    private var uiVisible = 0
    private var lastReport = 0L

    private val plays = store.loadPlays("plays.json")          // this phone's own counts
    private val playsOut = store.loadPlays("plays-out.json")   // ...that the hub hasn't had yet
    private val hubPlays = store.loadPlays("plays-hub.json")   // everyone's, as the hub last said
    private var hubPlaysRev = -1L
    private var playsRev = 0
    private var listen = 0          // bumped each time a track starts over...
    private var counted = -1        // ...and set to it once that listen has been counted
    private var listenTrack: String? = null
    private val listening = Listening(store)
    /** The months as the hub last told them, newest first (kept for when the phone is off Wi-Fi). */
    var months: List<MonthHeard> = monthsFrom(store.loadJson("listening-hub.json")); private set

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var watcher: Job? = null
    private var heartbeat: Job? = null

    val player: ExoPlayer by lazy { buildPlayer() }

    // ---------- setup

    private fun buildPlayer(): ExoPlayer {
        // Float output keeps 24-bit FLAC at full depth all the way to the mixer; nothing here
        // resamples, normalises or scales: volume is left to the system.
        val renderers = DefaultRenderersFactory(context).setEnableAudioFloatOutput(true)
        // The phone's own copies (content://) go straight through; anything from the hub waits for Wi-Fi.
        val http = WifiOnlySource.Factory(DefaultHttpDataSource.Factory(), wait = true)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).setLoadErrorHandlingPolicy(WifiOnlyErrors())
        val exo = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSkipSilenceEnabled(false)
            .build()
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.shuffleModeEnabled = false
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                listening.tally(if (player.isPlaying) item?.mediaId else null)
                // Moving on by itself means the last track played to its end.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) checkPlay(ended = true)
                if (item?.mediaId != listenTrack || reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) listen++
                listenTrack = item?.mediaId
                Net.playingUri = item?.localConfiguration?.uri?.toString()
                if (item != null && !Net.free && !onPhone(item)) {
                    main.post { offWifi() }
                    return
                }
                if (item != null && current != null) report()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listening.tally(if (isPlaying) player.currentMediaItem?.mediaId else null)
                if (isPlaying) {
                    // Sound is coming out: whatever went wrong before is over.
                    failures = 0
                    retried = null
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady != playing && current != null) {
                    Log.i(TAG, "playWhenReady=$playWhenReady reason=$reason")
                    if (!playWhenReady) {
                        checkPlay()
                        when (reason) {
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> stopped("Paused: another app is playing")
                            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> stopped("Paused: headphones disconnected")
                            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> stopped("Paused from another app")
                        }
                    }
                    playing = playWhenReady
                    if (playing) resumeOnWifiUntil = 0
                    if (!playing) speakerAllowedUntil = maxOf(speakerAllowedUntil, SystemClock.elapsedRealtime() + SPEAKER_GRACE_MS)
                    report(claim = playing)
                    loops()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && current != null && playing) {
                    checkPlay(ended = true)
                    wrap()
                }
            }

            override fun onPlayerError(error: PlaybackException) = failed(error)
        })
        return exo
    }

    private fun start() {
        Net.init(context)
        Net.onChange { main.post { networkChanged() } }
        val (etag, lib) = store.loadLibrary()
        libraryEtag = etag
        library = lib
        hubState = store.loadHubState()
        current?.let { c -> sessions[c]?.let { order = it.order } }
        context.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = routeChanged()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = routeChanged()
        }, main)
        rescanLocal()
        scope.launch { sender() }
        publish()
        refresh()
    }

    private fun routeChanged() {
        // A new route means a new decision: confirming the speaker once doesn't carry over. Only a press
        // of play is gated, so clearing this never stops what's already playing.
        speakerAllowedUntil = 0
        publish()
    }

    fun setHub(url: String, device: String) {
        store.hubUrl = url
        store.device = device
        hub = Hub(store.hubUrl)
        hubOnline = false
        libraryEtag = ""
        watcher?.cancel()
        watcher = null
        refresh()
        wake.trySend(Unit)
    }

    private fun networkChanged() {
        Log.i(TAG, if (Net.free) "on Wi-Fi" else "off Wi-Fi: music from the phone only, progress reports only")
        if (Net.free) {
            wake.trySend(Unit)   // whatever waited for Wi-Fi (play counts, a full order) goes now
            refresh()
            // The music stopped only because the stream went away: it carries on, as long as that won't be
            // out of the phone's speaker.
            val playlist = current
            if (!playing && playlist != null && SystemClock.elapsedRealtime() < resumeOnWifiUntil) scope.launch {
                // ...but not if another device has started playing since: that would take the music away from it.
                val got = withContext(Dispatchers.IO) { runCatching { fetchState(null) }.getOrNull() } ?: return@launch
                onHub(got)
                val active = hubState?.active
                val mine = active == null || active.device == store.device || active.since <= claimedAt
                if (mine && !playing && current == playlist && SystemClock.elapsedRealtime() < resumeOnWifiUntil && !Speaker.isPhone(context)) {
                    Log.i(TAG, "back on Wi-Fi: carrying on")
                    requestPlay(playlist)
                }
            }
        }
        // Off Wi-Fi a streamed track plays what it has buffered, then the player's error moves on.
        publish()
    }

    private fun onPhone(item: MediaItem) = item.localConfiguration?.uri?.scheme.let { it != "http" && it != "https" }

    /** Off Wi-Fi: carry on from the next track that's on the phone, passing over the ones that aren't.
     * Unless the Wi-Fi is already back (it blinked, or Android checked it again), when the stream just goes on. */
    private fun offWifi() {
        val exo = player
        if (exo.mediaItemCount == 0 || current == null) return
        if (Net.check()) {
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()   // the same track, from where it was
            return
        }
        var i = exo.currentMediaItemIndex
        while (i < exo.mediaItemCount && !onPhone(exo.getMediaItemAt(i))) i++
        if (i >= exo.mediaItemCount) {
            if (playing) {
                playing = false
                exo.pause()
                stopped("Off Wi-Fi, and the rest isn't on the phone")
                resumeOnWifiUntil = SystemClock.elapsedRealtime() + RESUME_ON_WIFI_MS
                report()
            }
            publish()
            return
        }
        if (i != exo.currentMediaItemIndex) {
            say("Off Wi-Fi: skipped songs not on the phone")
            exo.seekTo(i, 0)
        }
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        if (playing) exo.play()
        publish()
    }

    /** A track that wouldn't play. It gets one more go first: the phone's own copy may have moved (Syncthing
     * replaces a file, and the media index gives it a new address) or a stream may only have hiccuped. */
    private fun failed(error: PlaybackException) {
        val exo = player
        val item = exo.currentMediaItem
        Log.i(TAG, "error ${error.errorCodeName} on ${item?.localConfiguration?.uri?.scheme} ${item?.mediaId}: ${error.message}")
        if (current == null || item == null) return
        if (WifiOnlyErrors.notOnWifi(error)) return offWifi()
        val id = item.mediaId
        if (retried != id) {
            retried = id
            scope.launch {
                if (item.localConfiguration?.uri?.scheme == "content") refreshLocal()
                val i = exo.currentMediaItemIndex
                if (current == null || exo.currentMediaItem?.mediaId != id || exo.playbackState != Player.STATE_IDLE) return@launch
                val uri = source(id)
                if (exo.currentMediaItem?.localConfiguration?.uri != uri) {
                    val at = exo.currentPosition
                    exo.replaceMediaItem(i, exo.getMediaItemAt(i).buildUpon().setUri(uri).build())
                    exo.seekTo(i, at)
                }
                exo.prepare()
            }
            return
        }
        failures++
        retried = null
        if (failures < minOf(5, exo.mediaItemCount)) {
            say("Skipped a song that wouldn't play")
            val next = exo.currentMediaItemIndex + 1
            if (next >= exo.mediaItemCount) wrap()
            else {
                exo.seekTo(next, 0)
                exo.prepare()
            }
        } else {
            playing = false
            exo.pause()
            stopped(when {
                local.isEmpty() && !hubOnline -> "The music isn't on this phone and the hub can't be reached"
                !Net.free && local.isEmpty() -> "Off Wi-Fi, and the music isn't on the phone"
                else -> "Stopped: $failures songs in a row wouldn't play (${error.errorCodeName})"
            })
            report()
        }
        publish()
    }

    /** A line at the top of the screen for a little while. */
    private fun say(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = if (text.isEmpty()) null else scope.launch {
            delay(MESSAGE_MS)
            if (message == text) {
                message = ""
                publish()
            }
        }
    }

    /** The music stopped without anyone here pressing pause: say why, and keep it for Settings. */
    private fun stopped(why: String) {
        Log.i(TAG, "stopped: $why")
        say(why)
        store.noteStop(why)
    }

    suspend fun refreshLocal() = rescanLocal().await()

    /** Look again for the phone's own copies, and point the queue at whatever that finds. */
    private fun rescanLocal(): Deferred<Unit> = scope.async {
        val ids = library.keys.toList()
        val found = withContext(Dispatchers.IO) { LocalFiles.find(context, ids) }
        Log.i(TAG, "local copies: ${found?.size ?: "index unreadable"} of ${ids.size}")
        if (found != null) {
            local = found
            scanned = true
            useLocal()
        }
        publish()
    }.also { scan = it }

    /** Before a queue is built: a cold start (a widget tap after the system freed the app) gets to play
     * before the first look at the phone's files is done, and without this the whole queue streamed. */
    private suspend fun awaitLocal(wait: Long = LOCAL_WAIT_MS) {
        if (scanned) return
        val pending = scan?.takeIf { it.isActive } ?: rescanLocal()
        withTimeoutOrNull(wait) { pending.await() }
    }

    /** Swap queued tracks to the phone's copy once it's known (or back to the hub if the copy went away).
     * The track that's on is left alone, so nothing jumps. */
    private fun useLocal() {
        val on = player.currentMediaItemIndex
        fun stale(i: Int) = i != on && player.getMediaItemAt(i).let { it.localConfiguration?.uri != source(it.mediaId) }
        var i = 0
        while (i < player.mediaItemCount) {
            if (!stale(i)) { i++; continue }
            var end = i
            while (end < player.mediaItemCount && stale(end)) end++
            player.replaceMediaItems(i, end, (i until end).map { player.getMediaItemAt(it).let { m -> m.buildUpon().setUri(source(m.mediaId)).build() } })
            i = end
        }
    }

    private fun source(id: String): Uri = local[id] ?: Uri.parse(hub.fileUrl(id))

    /** Fetch the hub's state and library now. */
    fun refresh() {
        if (!Net.free) {
            // the hub's live state and the library wait for Wi-Fi; only progress goes out
            loops()
            publish()
            return
        }
        scope.launch {
            val got = withContext(Dispatchers.IO) { runCatching { fetchState(null) }.getOrNull() }
            if (got != null) onHub(got) else offline()
            loops()
        }
    }

    private fun fetchState(rev: Long?): Pair<JSONObject, Double> {
        val sent = System.currentTimeMillis() / 1000.0
        val reply = if (rev == null) hub.request("GET", "/api/state")
        else hub.request("GET", "/api/wait?rev=$rev&timeout=45", timeoutMs = 60_000)
        if (reply.code != 200) throw java.io.IOException("hub answered ${reply.code}")
        val o = JSONObject(reply.body)
        val received = System.currentTimeMillis() / 1000.0
        if (o.optString("library").isNotEmpty() || o.has("library")) {
            val rev2 = o.optJSONObject("library")?.optString("rev") ?: ""
            if ("\"$rev2\"" != libraryEtag) {
                val lib = hub.request("GET", "/api/library", timeoutMs = 30_000, etag = libraryEtag.ifEmpty { null })
                if (lib.code == 200) {
                    store.saveLibrary(lib.etag ?: "\"$rev2\"", lib.body)
                    o.put("_libraryChanged", true)
                }
            }
        }
        store.saveHubState(o.toString())
        return o to (if (rev == null) (sent + received) / 2 else received)
    }

    private suspend fun onHub(got: Pair<JSONObject, Double>) {
        val (o, received) = got
        val state = HubState.from(o)
        hubOffset = state.now - received
        hubOnline = true
        hubState = state
        if (o.optBoolean("_libraryChanged")) {
            val (etag, lib) = store.loadLibrary()
            libraryEtag = etag
            library = lib
            refreshLocal()
        }
        val active = state.active
        if (playing && active != null && active.device != store.device && active.since > claimedAt) yieldTo(active.device)
        if (state.playsRev != hubPlaysRev && uiVisible > 0) fetchPlays()
        publish()
    }

    private fun fetchPlays() {
        scope.launch {
            val o = withContext(Dispatchers.IO) { runCatching { hub.json("GET", "/api/plays", timeoutMs = 20_000) }.getOrNull() } ?: return@launch
            if (!o.has("plays")) return@launch
            hubPlaysRev = o.optLong("playsRev")
            hubPlays.clear()
            hubPlays.putAll(playsFrom(o.optJSONObject("plays")))
            store.savePlays("plays-hub.json", hubPlays)
            playsRev++
            publish()
        }
    }

    private fun offline() {
        if (hubOnline) {
            hubOnline = false
            publish()
        }
    }

    /** Keep a request open on the hub while it matters: while playing (to hear about a takeover)
     * or while the screen is up. Otherwise the phone stays quiet. */
    private fun loops() {
        val wanted = playing || uiVisible > 0
        if (wanted && watcher?.isActive != true && hub.base.isNotBlank()) {
            watcher = scope.launch {
                var wait = 2000L
                var rev: Long? = null
                while (isActive && (playing || uiVisible > 0)) {
                    if (!Net.free) {
                        Net.state.first { it }
                        rev = null
                        continue
                    }
                    val got = withContext(Dispatchers.IO) { runCatching { fetchState(rev) }.getOrNull() }
                    if (got == null) {
                        offline()
                        rev = null
                        delay(wait)
                        wait = minOf(wait * 2, 60_000)
                        continue
                    }
                    wait = 2000L
                    rev = got.first.optLong("rev")
                    onHub(got)
                }
            }
        }
        if (playing && heartbeat?.isActive != true) {
            heartbeat = scope.launch {
                while (isActive && playing) {
                    delay(HEARTBEAT_MS)
                    if (playing) {
                        listening.tally(if (player.isPlaying) player.currentMediaItem?.mediaId else null)
                        checkPlay()
                        if (Net.free || SystemClock.elapsedRealtime() - lastReport >= HEARTBEAT_MOBILE_MS) report()
                    }
                }
            }
        }
    }

    fun uiShown() {
        uiVisible++
        refresh()
    }

    fun uiHidden() {
        uiVisible = maxOf(0, uiVisible - 1)
    }

    // ---------- reading

    private fun hubNow() = System.currentTimeMillis() / 1000.0 + hubOffset
    private fun custom(): Map<String, CustomPlaylist> = hubState?.playlists ?: emptyMap()
    private fun modes(): Map<String, String> = hubState?.modes ?: emptyMap()

    fun slots(): Map<String, String> {
        val out = HashMap(Core.defaultSlots(Core.folders(library.keys).keys))
        hubState?.slots?.let { out.putAll(it) }
        return out
    }

    fun tracksOf(playlist: String) = Core.playlistTracks(playlist, library.keys, custom(), library)

    private var taggedFor: Map<String, Track>? = null
    private var taggedCache: Pair<Map<String, List<String>>, Map<String, List<String>>> = emptyMap<String, List<String>>() to emptyMap()

    /** Every artist and album, worked out again only when the library changes. */
    private fun tagged(): Pair<Map<String, List<String>>, Map<String, List<String>>> {
        if (taggedFor !== library) {
            taggedCache = Core.tagged(library)
            taggedFor = library
        }
        return taggedCache
    }
    /** A playlist's run from the song it's on: what's still to come, then what has played; null if this phone doesn't know. */
    fun upNext(playlist: String): List<String>? {
        val (run, at) = if (playlist == current && player.mediaItemCount > 0) order to player.currentMediaItemIndex
        else sessions[playlist]?.let { it.order to it.index } ?: return null
        if (run.isEmpty() || at !in run.indices) return null
        return run.subList(at, run.size) + run.subList(0, at)
    }

    fun track(id: String?): Track? = id?.let { library[it] ?: Track.guess(it) }
    fun artUrl(track: Track?, size: Int = 640): String? = if (track != null && track.art && hub.base.isNotBlank()) hub.artUrl(track.id, size) else null

    /** The phone's own copy of the track a hub cover address is for, if there is one. */
    fun localForArt(uri: Uri): Uri? = uri.path?.substringAfter("/art/", "")?.takeIf { it.isNotEmpty() }?.let { local[it] }
    fun isLocal(id: String) = id in local
    fun localUri(id: String): Uri? = local[id]

    /** Everyone's count, or this phone's own when the hub hasn't heard it yet. */
    fun playsOf(id: String): Play = maxOf(hubPlays[id] ?: Play.NONE, plays[id] ?: Play.NONE)

    /** The songs heard last, newest first. */
    fun recent(limit: Int = 60): List<Track> = library.keys.map { it to playsOf(it).last }.filter { it.second > 0 }
        .sortedByDescending { it.second }.take(limit).mapNotNull { track(it.first) }

    fun position(): Long = if (current != null && player.mediaItemCount > 0) player.currentPosition else
        ((sessions[current ?: ""]?.position ?: 0.0) * 1000).toLong()

    fun duration(): Long {
        val d = player.duration
        return if (d != C.TIME_UNSET && d > 0) d else ((track(currentTrack())?.duration ?: 0.0) * 1000).toLong()
    }

    private fun currentTrack(): String? = if (player.mediaItemCount > 0) player.currentMediaItem?.mediaId
    else sessions[current ?: ""]?.track

    fun publish() {
        val custom = custom()
        val slots = slots()
        val bySlot = slots.entries.associate { it.value to it.key }
        val remote = hubState?.sessions ?: emptyMap()
        val views = ArrayList<PlaylistView>()
        val folderMap = Core.folders(library.keys)
        data class Last(val track: String?, val device: String?, val playing: Boolean, val updated: Double)
        fun last(id: String): Last {
            val mine = sessions[id]
            val theirs = remote[id]
            return if (mine != null && (mine.unsent || theirs == null || mine.updated >= theirs.updated)) Last(mine.track, mine.device, mine.playing, mine.updated)
            else if (theirs != null) Last(theirs.track, theirs.device, theirs.playing, theirs.updated) else Last(null, null, false, 0.0)
        }
        for (id in folderMap.keys.sortedWith(compareBy(Core.natural) { it.substring(2) })) {
            val (t, d, p, u) = last(id)
            views.add(PlaylistView(id, Core.playlistName(id, custom), id.substring(2), "folder", folderMap[id]!!.size, bySlot[id] ?: "",
                modes()[id] ?: "shuffle", track(t), d, p, updated = u))
        }
        for ((id, c) in custom) {
            val (t, d, p, u) = last(id)
            views.add(PlaylistView(id, c.name, "", "custom", tracksOf(id).size, bySlot[id] ?: "", modes()[id] ?: "shuffle", track(t), d, p, updated = u))
        }
        val (artists, albums) = tagged()
        for ((kind, found) in listOf("artist" to artists, "album" to albums)) {
            for (id in found.keys.sortedWith(compareBy(Core.natural) { it.substring(2) })) {
                val (t, d, p, u) = last(id)
                val by = if (kind == "album") Core.albumBy(id, found[id]!!, library) else ""
                views.add(PlaylistView(id, Core.playlistName(id, custom), "", kind, found[id]!!.size, bySlot[id] ?: "",
                    modes()[id] ?: "shuffle", track(t), d, p, by, u))
            }
        }
        val active = hubState?.active
        var elsewhere: Elsewhere? = null
        if (active != null && active.device != store.device) {
            val s = remote[active.playlist]
            if (s != null && s.device == active.device) {
                elsewhere = Elsewhere(active.device, active.playlist, Core.playlistName(active.playlist, custom), s.playing, track(s.track))
            }
        }
        val idx = if (player.mediaItemCount > 0) player.currentMediaItemIndex else sessions[current ?: ""]?.index ?: 0
        _state.value = UiState(
            ready = true, playing = playing, playlist = current,
            playlistName = current?.let { Core.playlistName(it, custom) } ?: "", track = track(currentTrack()),
            index = idx, count = if (player.mediaItemCount > 0) player.mediaItemCount else sessions[current ?: ""]?.order?.size ?: 0,
            slots = slots, playlists = views, elsewhere = elsewhere, hubOnline = hubOnline, hubUrl = hub.base,
            device = store.device, message = message, askSpeaker = askSpeaker, output = Speaker.outputName(context), starting = starting != null, pending = pendingPlaylist.takeIf { starting != null },
            onSpeaker = Speaker.isPhone(context), localCount = local.size, libraryCount = library.size,
            customs = custom.values.toList(), playsRev = playsRev, onWifi = Net.free, mobileUsed = Net.used(),
        )
        Widgets.update(context, _state.value)
    }

    // ---------- playing

    private fun speakerOk() = !Speaker.isPhone(context) || playing || SystemClock.elapsedRealtime() < speakerAllowedUntil

    /** Every way of starting music comes through here, so the "out loud?" question can't be skipped. */
    fun requestPlay(playlist: String, track: String? = null, fresh: Boolean = false, confirmed: Boolean = false): Boolean {
        if (!confirmed && !speakerOk()) {
            askSpeaker = PendingPlay(playlist, track, fresh)
            publish()
            return false
        }
        if (confirmed) speakerAllowedUntil = SystemClock.elapsedRealtime() + SPEAKER_GRACE_MS
        askSpeaker = null
        resumeOnWifiUntil = 0
        // Now, while whatever asked (the screen, a widget's tap) still counts as the user's doing: Android only
        // lets a service go foreground from the background in a few cases, and without it the music dies
        // when the app is swiped away or the system wants the memory.
        ensureService()
        // A second press while the first is still looking (a double tap, or 1 then 2 on a cold start): the last one wins.
        starting?.cancel()
        pendingPlaylist = playlist
        starting = scope.launch {
            try {
                play(playlist, track, fresh)
            } finally {
                if (starting === coroutineContext[Job]) starting = null
                publish()
            }
        }
        publish()
        return true
    }

    fun answerSpeaker(yes: Boolean) {
        val pending = askSpeaker ?: return
        askSpeaker = null
        context.getSystemService(NotificationManager::class.java)?.cancel(7)
        if (yes) requestPlay(pending.playlist, pending.track, pending.fresh, confirmed = true) else publish()
    }

    fun slot(n: String): Boolean {
        val playlist = slots()[n] ?: run { say("Nothing is set for $n"); publish(); return false }
        if (playing && current == playlist) {
            pause()
            return true
        }
        return requestPlay(playlist)
    }

    fun toggle() = if (playing) { pause(); true } else resume()

    fun resume(): Boolean {
        val playlist = current ?: hubState?.active?.playlist ?: slots()["1"] ?: return false
        return requestPlay(playlist)
    }

    fun pause() {
        resumeOnWifiUntil = 0
        if (player.playWhenReady) player.pause()
        else if (playing) {
            playing = false
            report()
        }
    }

    fun next() = step(1)
    fun previous() = step(-1)

    private fun step(by: Int) {
        val playlist = current ?: return
        if (player.mediaItemCount == 0 || !playing) {
            sessions[playlist]?.let { s ->
                sessions[playlist] = s.copy(index = Math.floorMod(s.index + by, maxOf(s.order.size, 1)), position = 0.0, updated = hubNow(),
                    unsent = true, playing = false, device = store.device)
            }
            requestPlay(playlist)
            return
        }
        checkPlay()
        if (by < 0 && player.currentPosition > 4000) {
            player.seekTo(0)
            listen++
            report()
            return
        }
        val target = player.currentMediaItemIndex + by
        if (target >= player.mediaItemCount) wrap() else player.seekTo(maxOf(target, 0), 0)
    }

    fun seek(ms: Long) {
        if (player.mediaItemCount == 0) return
        checkPlay()
        player.seekTo(ms)
        report()
    }

    /** Where a press of play on a playlist starts: its run through it, the song and the second. */
    private class Plan(val order: List<String>, val index: Int, val position: Double)

    /** Works out where to start: the hub's spot for the playlist (it may have moved on on another device), or this
     * phone's own when it's newer or the hub can't be reached. Null when the playlist has nothing in it. */
    private suspend fun plan(playlist: String, track: String?, fresh: Boolean, localWait: Long = LOCAL_WAIT_MS): Plan? {
        val tracks = tracksOf(playlist)
        if (tracks.isEmpty()) return null
        awaitLocal(localWait)
        val mode = modes()[playlist] ?: "shuffle"
        var base: Session? = null
        if (!fresh) {
            val remote = fetchSession(playlist)
            val mine = sessions[playlist]
            base = if (remote != null && mine != null && mine.unsent && mine.updated > remote.updated) mine else remote ?: mine
        }
        if (base != null && base.order.isNotEmpty()) {
            val live = Core.livePosition(base.position, base.playing, base.device, base.updated, hubNow(), store.device)
            val fit = Core.reconcile(base.order, base.index, tracks, mode, rng)
            if (track != null && track in fit.order) return Plan(fit.order, fit.order.indexOf(track), 0.0)
            val (i, p) = Core.resumePoint(fit.order, fit.index, live, library[fit.order[fit.index]]?.duration ?: 0.0, fit.sameTrack)
            return Plan(fit.order, i, p)
        }
        val fresh2 = Core.newOrder(tracks, mode, rng, first = track)
        return Plan(fresh2, if (track != null) maxOf(fresh2.indexOf(track), 0) else 0, 0.0)
    }

    /** Leaving the playlist that's loaded for another, mid-song: keep its exact spot, and tell the hub it isn't
     * playing any more. Only if this phone was the one playing it, or it would put back a spot that's been moved on. */
    private fun leave(next: String, wasPlaying: Boolean) {
        val old = current ?: return
        if (old == next || !wasPlaying || player.mediaItemCount == 0) return
        sessions[old] = Session(old, order, player.currentMediaItemIndex, player.currentPosition / 1000.0, false,
            store.device, hubNow(), unsent = true)
        outbox[old] = (outbox[old]?.first ?: false) to System.currentTimeMillis()
    }

    private fun fresh() {
        claimedAt = hubNow()
        say("")
        failures = 0
        retried = null
    }

    private suspend fun play(playlist: String, track: String?, fresh: Boolean) {
        checkPlay()
        val p = plan(playlist, track, fresh) ?: run {
            say("Nothing to play there yet")
            publish()
            return
        }
        val sameList = current == playlist && order == p.order && player.mediaItemCount == p.order.size
        val wasPlaying = playing
        playing = true
        fresh()
        if (sameList) {
            if (player.currentMediaItemIndex != p.index || kotlin.math.abs(player.currentPosition / 1000.0 - p.position) > 3 || track != null) {
                if (player.currentMediaItemIndex == p.index) listen++  // the same track again, from the top
                player.seekTo(p.index, (p.position * 1000).toLong())
            }
        } else {
            leave(playlist, wasPlaying)
            current = playlist
            store.current = playlist
            order = p.order
            player.setMediaItems(p.order.map { item(it) }, p.index, (p.position * 1000).toLong())
        }
        player.prepare()
        player.play()
        ensureService()
        report(claim = true)
        loops()
    }

    /** A headset's play button or the system's resume control after the process had died, through Media3: the
     * same start as a press of play, handed back as the queue for Media3 to load and play. Null when it shouldn't
     * play now (out of the phone's speaker, so it asks instead). Quick, because the service has to go foreground
     * within seconds of being started for this. */
    fun resumption(done: (Triple<List<MediaItem>, Int, Long>?) -> Unit) {
        if (!speakerOk()) {
            askFromOutside()
            return done(null)
        }
        val playlist = current ?: hubState?.active?.playlist ?: slots()["1"] ?: return done(null)
        scope.launch {
            checkPlay()
            val p = withTimeoutOrNull(4_000) { plan(playlist, null, false, localWait = 1_500) }
            if (p == null || playing || player.mediaItemCount > 0) return@launch done(null)
            current = playlist
            store.current = playlist
            order = p.order
            fresh()
            // Media3 plays it, and the player's own callback makes the claim.
            done(Triple(p.order.map { item(it) }, p.index, (p.position * 1000).toLong()))
        }
    }

    private fun item(id: String): MediaItem {
        val t = track(id)!!
        val meta = MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist.ifEmpty { null }).setAlbumTitle(t.album.ifEmpty { null })
            .setIsPlayable(true).setIsBrowsable(false)
        artUrl(t)?.let { meta.setArtworkUri(Uri.parse(it)) }
        return MediaItem.Builder().setMediaId(id).setUri(source(id)).setMediaMetadata(meta.build()).build()
    }

    private fun wrap() {
        val playlist = current ?: return
        val tracks = tracksOf(playlist)
        if (tracks.isEmpty()) {
            playing = false
            report()
            return
        }
        val last = player.currentMediaItem?.mediaId
        order = Core.newOrder(tracks, modes()[playlist] ?: "shuffle", rng, avoidFirst = last)
        player.setMediaItems(order.map { item(it) }, 0, 0)
        player.prepare()
        player.play()
        report(claim = true)
    }

    private fun ensureService() {
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
    }

    private suspend fun fetchSession(playlist: String): Session? {
        val have = sessions[playlist]?.orderHash ?: ""
        return withTimeoutOrNull(1800) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val sent = System.currentTimeMillis() / 1000.0
                    val o = hub.json("GET", "/api/session?playlist=${Hub.q(playlist)}&have=$have", timeoutMs = 1600, progress = true)
                    hubOffset = o.getDouble("now") - (sent + System.currentTimeMillis() / 1000.0) / 2
                    hubOnline = true
                    o.optJSONObject("session")?.let { Session.from(it, sessions[playlist]?.order) }
                }.getOrNull()
            }
        }
    }

    private fun yieldTo(device: String) {
        Log.i(TAG, "yielding to $device")
        playing = false
        player.pause()
        current?.let { c -> sessions[c]?.let { sessions[c] = it.copy(unsent = false, playing = false) }; outbox.remove(c) }
        store.saveOutbox(outbox)
        stopped("Playing on $device now")
        publish()
    }

    // ---------- play counts

    /** Count the track that's on as played, once per listen, when enough of it has been heard
     * (or it played to its end). */
    private fun checkPlay(ended: Boolean = false) {
        if (current == null || counted == listen) return
        val id = listenTrack ?: return
        if (!ended) {
            if (!playing || player.mediaItemCount == 0 || player.currentMediaItem?.mediaId != id) return
            val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000.0 } ?: track(id)?.duration ?: 0.0
            if (!Core.countsAsPlay(player.currentPosition / 1000.0, d)) return
        }
        counted = listen
        val now = hubNow()
        val mine = Play((plays[id]?.count ?: 0) + 1, now)
        plays[id] = mine
        playsOut[id] = mine
        hubPlays[id] = Play((hubPlays[id]?.count ?: 0) + 1, now)
        store.savePlays("plays.json", plays)
        store.savePlays("plays-out.json", playsOut)
        listening.credit(id, plays = 1)
        playsRev++
        wake.trySend(Unit)
    }

    /** Month by month, fresh from the hub when on Wi-Fi, otherwise as it last said. */
    suspend fun loadMonths(): List<MonthHeard> {
        val o = withContext(Dispatchers.IO) { runCatching { hub.json("GET", "/api/listening", timeoutMs = 15_000) }.getOrNull() }
        if (o != null && o.has("months")) {
            store.saveJson("listening-hub.json", o)
            months = monthsFrom(o)
        }
        return months
    }

    private fun monthsFrom(o: JSONObject?): List<MonthHeard> =
        o?.optJSONArray("months")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(MonthHeard::from) } } ?: emptyList()

    /** Send this phone's running totals, and its listening time; they're absolute, so a resend is harmless. */
    private suspend fun sendPlays(): Boolean {
        val sending = HashMap(playsOut)
        val listened = if (listening.waiting()) listening.sending() else null
        val reply = withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("source", store.source).put("plays", playsJson(sending))
                listened?.let { body.put("listened", it) }
                hub.json("POST", "/api/plays", body)
            }.getOrNull()
        } ?: return false
        listened?.let { listening.sent(it) }
        for ((k, v) in sending) if (playsOut[k] == v) playsOut.remove(k)
        store.savePlays("plays-out.json", playsOut)
        if (Core.mergePlays(hubPlays, playsFrom(reply.optJSONObject("plays")))) {
            store.savePlays("plays-hub.json", hubPlays)
            playsRev++
            publish()
        }
        return true
    }

    // ---------- reporting

    private fun report(claim: Boolean = false) {
        val playlist = current ?: return
        lastReport = SystemClock.elapsedRealtime()
        val idx = if (player.mediaItemCount > 0) player.currentMediaItemIndex else sessions[playlist]?.index ?: 0
        val pos = if (player.mediaItemCount > 0) player.currentPosition / 1000.0 else sessions[playlist]?.position ?: 0.0
        sessions[playlist] = Session(playlist, order, idx, pos, playing, store.device, hubNow(), unsent = true)
        store.saveSessions(sessions)
        val waiting = outbox[playlist]
        // A claim still waiting to go keeps the time it was made, so a newer press elsewhere still beats it.
        val at = if (!claim && waiting?.first == true) waiting.second else System.currentTimeMillis()
        outbox[playlist] = (claim || waiting?.first == true) to at
        store.saveOutbox(outbox)
        wake.trySend(Unit)
        publish()
    }

    private suspend fun sender() {
        while (true) {
            // play counts aren't progress, so they wait for Wi-Fi
            val plays = (playsOut.isNotEmpty() || listening.due(playing)) && hub.base.isNotBlank() && Net.free
            if (outbox.isEmpty() && !plays) wake.receive()
            if (plays && !sendPlays()) {
                offline()
                withTimeoutOrNull(20_000) { wake.receive() }
                continue
            }
            val (playlist, entry) = outbox.entries.firstOrNull()?.toPair() ?: continue
            val session = sessions[playlist]
            if (session == null) {
                outbox.remove(playlist); continue
            }
            outbox.remove(playlist)
            val body = JSONObject().put("device", store.device).put("playlist", playlist).put("index", session.index)
                .put("position", session.position).put("playing", session.playing).put("orderHash", session.orderHash)
                .put("claim", entry.first)
            // Over mobile data the order (which can be long) only goes when the hub says it hasn't got it.
            if (entry.first && Net.free) body.put("order", JSONArray(session.order))
            val reply = withContext(Dispatchers.IO) {
                runCatching {
                    body.put("age", (System.currentTimeMillis() - entry.second) / 1000.0)
                    var r = hub.json("POST", "/api/session", body, progress = true)
                    if (r.optBoolean("needOrder")) {
                        body.put("order", JSONArray(session.order))
                        r = hub.json("POST", "/api/session", body, progress = true)
                    }
                    r
                }.getOrNull()
            }
            if (reply == null) {
                val newer = outbox[playlist]
                // A claim keeps the time of its press, or it would look newer than presses made elsewhere since
                val at = if (newer == null || (entry.first && !newer.first)) entry.second else newer.second
                outbox[playlist] = ((newer?.first ?: false) || entry.first) to at
                store.saveOutbox(outbox)
                offline()
                // wait for the network, or for something new to say
                withTimeoutOrNull(20_000) { wake.receive() }
                continue
            }
            hubOnline = true
            store.saveOutbox(outbox)
            if (sessions[playlist] === session) sessions[playlist] = session.copy(unsent = false)
            if (reply.optBoolean("superseded") && playlist == current && playing) {
                yieldTo(reply.optJSONObject("active")?.optString("device") ?: "another device")
            }
            store.saveSessions(sessions)
            publish()
        }
    }

    // ---------- playlists, slots and modes (all live on the hub)

    fun hubEdit(method: String, path: String, body: JSONObject?, then: (JSONObject?) -> Unit = {}) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { hub.json(method, path, body) }.getOrNull() }
            if (r == null) {
                say(if (Net.free) "Can't reach the hub, so that wasn't saved" else "Off Wi-Fi, so that wasn't saved")
                publish()
            }
            refresh()
            then(r)
        }
    }

    /** Deletes songs from the library for good (the hub moves the files to its trash). A song that's
     * playing here is skipped once it's gone, since its file can't be streamed any more. */
    fun deleteTracks(ids: List<String>, then: () -> Unit = {}) {
        if (ids.isEmpty()) return
        hubEdit("POST", "/api/tracks/delete", JSONObject().put("tracks", JSONArray(ids))) { r ->
            if (r != null) {
                val deleted = r.optJSONArray("deleted")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
                if (playing && currentTrack() in deleted) next()
                val n = deleted.size
                say(r.optString("error").ifEmpty { if (n == 1) "Deleted ${track(ids[0])?.title ?: "1 song"}" else "Deleted $n songs" })
                publish()
            }
            then()
        }
    }

    fun setSlot(slot: String, playlist: String) = hubEdit("PUT", "/api/slots", JSONObject().put(slot, playlist))
    fun setMode(playlist: String, mode: String) = hubEdit("PUT", "/api/mode", JSONObject().put("playlist", playlist).put("mode", mode))

    fun clearMessage() {
        say("")
        publish()
    }

    // ---------- when play is pressed somewhere the screen isn't (notification, headset, lock screen)

    fun askFromOutside(): Boolean {
        val playlist = current ?: hubState?.active?.playlist ?: slots()["1"] ?: return false
        askSpeaker = PendingPlay(playlist, null, false)
        publish()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Play out loud?", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 7, Intent(context, ConfirmActivity::class.java).setAction(ConfirmActivity.ASK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(7, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Play out loud?").setContentText("Nothing is connected, so it would come from the phone's speaker.")
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(60_000).build())
        return false
    }

    fun gate(): Boolean = speakerOk()

    /** Android wouldn't let the service go foreground, so the music may be cut off soon. */
    fun foregroundRefused() {
        stopped("Android stopped the music running in the background")
        publish()
    }
}
