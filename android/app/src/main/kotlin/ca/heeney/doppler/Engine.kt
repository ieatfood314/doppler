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
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

data class PlaylistView(
    val id: String, val name: String, val path: String, val custom: Boolean, val count: Int, val slot: String,
    val mode: String, val lastTrack: Track?, val lastDevice: String?, val lastPlaying: Boolean,
)

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
    val customs: List<CustomPlaylist> = emptyList(),
    val playsRev: Int = 0,
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
        const val SPEAKER_GRACE_MS = 10 * 60_000L
        const val CHANNEL = "speaker"
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

    private var current: String? = store.current
    private var order: List<String> = emptyList()
    private var playing = false
    private var claimedAt = 0.0
    private var hubOffset = 0.0
    private var hubOnline = false
    private var message = ""
    private var errors = 0
    private var askSpeaker: PendingPlay? = null
    private var speakerAllowedUntil = 0L
    private var uiVisible = 0

    private val plays = store.loadPlays("plays.json")          // this phone's own counts
    private val playsOut = store.loadPlays("plays-out.json")   // ...that the hub hasn't had yet
    private val hubPlays = store.loadPlays("plays-hub.json")   // everyone's, as the hub last said
    private var hubPlaysRev = -1L
    private var playsRev = 0
    private var listen = 0          // bumped each time a track starts over...
    private var counted = -1        // ...and set to it once that listen has been counted
    private var listenTrack: String? = null

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
        val exo = ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSkipSilenceEnabled(false)
            .build()
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.shuffleModeEnabled = false
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // Moving on by itself means the last track played to its end.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) checkPlay(ended = true)
                listen++
                listenTrack = item?.mediaId
                if (item != null && current != null) {
                    errors = 0
                    report()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady != playing && current != null) {
                    if (!playWhenReady) checkPlay()
                    playing = playWhenReady
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

            override fun onPlayerError(error: PlaybackException) {
                errors++
                val exo = player
                if (errors < 4 && exo.mediaItemCount > 1) {
                    message = "Skipped a track that wouldn't play"
                    val next = (exo.currentMediaItemIndex + 1) % exo.mediaItemCount
                    exo.seekTo(next, 0)
                    exo.prepare()
                    exo.play()
                } else {
                    playing = false
                    message = if (local.isEmpty() && !hubOnline) "The music isn't on this phone and the hub can't be reached"
                    else "That wouldn't play: ${error.errorCodeName}"
                    report()
                }
                publish()
            }
        })
        return exo
    }

    private fun start() {
        val (etag, lib) = store.loadLibrary()
        libraryEtag = etag
        library = lib
        hubState = store.loadHubState()
        current?.let { c -> sessions[c]?.let { order = it.order } }
        context.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = routeChanged()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = routeChanged()
        }, main)
        scope.launch { refreshLocal() }
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

    suspend fun refreshLocal() {
        val ids = library.keys.toList()
        local = withContext(Dispatchers.IO) { LocalFiles.find(context, ids) }
        publish()
    }

    /** Fetch the hub's state and library now. */
    fun refresh() {
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
                        checkPlay()
                        report()
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

    fun tracksOf(playlist: String) = Core.playlistTracks(playlist, library.keys, custom())
    fun track(id: String?): Track? = id?.let { library[it] ?: Track.guess(it) }
    fun artUrl(track: Track?, size: Int = 640): String? = if (track != null && track.art && hub.base.isNotBlank()) hub.artUrl(track.id, size) else null
    fun isLocal(id: String) = id in local
    fun localUri(id: String): Uri? = local[id]

    /** Everyone's count, or this phone's own when the hub hasn't heard it yet. */
    fun playsOf(id: String): Play = maxOf(hubPlays[id] ?: Play.NONE, plays[id] ?: Play.NONE)

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
        fun last(id: String): Triple<String?, String?, Boolean> {
            val mine = sessions[id]
            val theirs = remote[id]
            return if (mine != null && (mine.unsent || theirs == null || mine.updated >= theirs.updated)) Triple(mine.track, mine.device, mine.playing)
            else if (theirs != null) Triple(theirs.track, theirs.device, theirs.playing) else Triple(null, null, false)
        }
        for (id in folderMap.keys.sortedWith(compareBy(Core.natural) { it.substring(2) })) {
            val (t, d, p) = last(id)
            views.add(PlaylistView(id, Core.playlistName(id, custom), id.substring(2), false, folderMap[id]!!.size, bySlot[id] ?: "",
                modes()[id] ?: "shuffle", track(t), d, p))
        }
        for ((id, c) in custom) {
            val (t, d, p) = last(id)
            views.add(PlaylistView(id, c.name, "", true, tracksOf(id).size, bySlot[id] ?: "", modes()[id] ?: "shuffle", track(t), d, p))
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
            device = store.device, message = message, askSpeaker = askSpeaker, output = Speaker.outputName(context),
            onSpeaker = Speaker.isPhone(context), localCount = local.size, libraryCount = library.size,
            customs = custom.values.toList(), playsRev = playsRev,
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
        scope.launch { play(playlist, track, fresh) }
        return true
    }

    fun answerSpeaker(yes: Boolean) {
        val pending = askSpeaker ?: return
        askSpeaker = null
        context.getSystemService(NotificationManager::class.java)?.cancel(7)
        if (yes) requestPlay(pending.playlist, pending.track, pending.fresh, confirmed = true) else publish()
    }

    fun slot(n: String): Boolean {
        val playlist = slots()[n] ?: run { message = "Nothing is set for $n"; publish(); return false }
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

    private suspend fun play(playlist: String, track: String?, fresh: Boolean) {
        val tracks = tracksOf(playlist)
        if (tracks.isEmpty()) {
            message = "Nothing to play there yet"
            publish()
            return
        }
        checkPlay()
        val mode = modes()[playlist] ?: "shuffle"
        var base: Session? = null
        if (!fresh) {
            val remote = fetchSession(playlist)
            val mine = sessions[playlist]
            base = if (remote != null && mine != null && mine.unsent && mine.updated > remote.updated) mine else remote ?: mine
        }
        val newOrder: List<String>
        var index: Int
        var position: Double
        if (base != null && base.order.isNotEmpty()) {
            val live = Core.livePosition(base.position, base.playing, base.device, base.updated, hubNow(), store.device)
            val fit = Core.reconcile(base.order, base.index, tracks, mode, rng)
            newOrder = fit.order
            if (track != null && track in newOrder) {
                index = newOrder.indexOf(track); position = 0.0
            } else {
                val (i, p) = Core.resumePoint(newOrder, fit.index, live, library[newOrder[fit.index]]?.duration ?: 0.0, fit.sameTrack)
                index = i; position = p
            }
        } else {
            newOrder = Core.newOrder(tracks, mode, rng, first = track)
            index = if (track != null) maxOf(newOrder.indexOf(track), 0) else 0
            position = 0.0
        }
        val sameList = current == playlist && order == newOrder && player.mediaItemCount == newOrder.size
        playing = true
        claimedAt = hubNow()
        message = ""
        errors = 0
        if (sameList) {
            if (player.currentMediaItemIndex != index || kotlin.math.abs(player.currentPosition / 1000.0 - position) > 3 || track != null) {
                if (player.currentMediaItemIndex == index) listen++  // the same track again, from the top
                player.seekTo(index, (position * 1000).toLong())
            }
        } else {
            current = playlist
            store.current = playlist
            order = newOrder
            player.setMediaItems(newOrder.map { item(it) }, index, (position * 1000).toLong())
        }
        player.prepare()
        player.play()
        ensureService()
        report(claim = true)
        loops()
    }

    private fun item(id: String): MediaItem {
        val t = track(id)!!
        val meta = MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist.ifEmpty { null }).setAlbumTitle(t.album.ifEmpty { null })
            .setIsPlayable(true).setIsBrowsable(false)
        artUrl(t)?.let { meta.setArtworkUri(Uri.parse(it)) }
        return MediaItem.Builder().setMediaId(id).setUri(local[id] ?: Uri.parse(hub.fileUrl(id))).setMediaMetadata(meta.build()).build()
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
                    val o = hub.json("GET", "/api/session?playlist=${Hub.q(playlist)}&have=$have", timeoutMs = 1600)
                    hubOffset = o.getDouble("now") - (sent + System.currentTimeMillis() / 1000.0) / 2
                    hubOnline = true
                    o.optJSONObject("session")?.let { Session.from(it, sessions[playlist]?.order) }
                }.getOrNull()
            }
        }
    }

    private fun yieldTo(device: String) {
        playing = false
        player.pause()
        current?.let { c -> sessions[c]?.let { sessions[c] = it.copy(unsent = false, playing = false) }; outbox.remove(c) }
        store.saveOutbox(outbox)
        message = "Playing on $device now"
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
        playsRev++
        wake.trySend(Unit)
    }

    /** Send this phone's running totals; they're absolute, so a resend is harmless. */
    private suspend fun sendPlays(): Boolean {
        val sending = HashMap(playsOut)
        val reply = withContext(Dispatchers.IO) {
            runCatching {
                hub.json("POST", "/api/plays", JSONObject().put("source", store.source).put("plays", playsJson(sending)))
            }.getOrNull()
        } ?: return false
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
        val idx = if (player.mediaItemCount > 0) player.currentMediaItemIndex else sessions[playlist]?.index ?: 0
        val pos = if (player.mediaItemCount > 0) player.currentPosition / 1000.0 else sessions[playlist]?.position ?: 0.0
        sessions[playlist] = Session(playlist, order, idx, pos, playing, store.device, hubNow(), unsent = true)
        store.saveSessions(sessions)
        val waiting = outbox[playlist]
        outbox[playlist] = (claim || waiting?.first == true) to System.currentTimeMillis()
        store.saveOutbox(outbox)
        wake.trySend(Unit)
        publish()
    }

    private suspend fun sender() {
        while (true) {
            if (outbox.isEmpty() && (playsOut.isEmpty() || hub.base.isBlank())) wake.receive()
            if (playsOut.isNotEmpty() && hub.base.isNotBlank() && !sendPlays()) {
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
            if (entry.first) body.put("order", JSONArray(session.order))
            val reply = withContext(Dispatchers.IO) {
                runCatching {
                    body.put("age", (System.currentTimeMillis() - entry.second) / 1000.0)
                    var r = hub.json("POST", "/api/session", body)
                    if (r.optBoolean("needOrder")) {
                        body.put("order", JSONArray(session.order))
                        r = hub.json("POST", "/api/session", body)
                    }
                    r
                }.getOrNull()
            }
            if (reply == null) {
                val newer = outbox[playlist]
                outbox[playlist] = ((newer?.first ?: false) || entry.first) to (newer?.second ?: entry.second)
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
                message = "Can't reach the hub, so that wasn't saved"
                publish()
            }
            refresh()
            then(r)
        }
    }

    fun setSlot(slot: String, playlist: String) = hubEdit("PUT", "/api/slots", JSONObject().put(slot, playlist))
    fun setMode(playlist: String, mode: String) = hubEdit("PUT", "/api/mode", JSONObject().put("playlist", playlist).put("mode", mode))

    fun clearMessage() {
        message = ""
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
}
