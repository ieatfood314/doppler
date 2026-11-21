package ca.heeney.doppler

import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

private sealed interface Screen {
    data object Home : Screen
    data object Library : Screen
    data object Recent : Screen
    data object Listening : Screen
    data class Wrapped(val year: Int) : Screen
    data class Playlist(val id: String) : Screen
    data class Browse(val kind: String) : Screen
    data object Settings : Screen
}

@Composable
fun App(engine: Engine, askFiles: () -> Unit, opening: String? = null, opened: () -> Unit = {}) {
    val state by engine.state.collectAsState()
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    // The widget's playing number lands on that playlist's page, with home behind it.
    LaunchedEffect(opening) {
        if (opening != null) {
            stack.clear()
            stack.addAll(listOf(Screen.Home, Screen.Playlist(opening)))
            opened()
        }
    }
    val screen = stack.last()
    BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
    Box(Modifier.fillMaxSize().background(Palette.Void)) {
        Starfield(Modifier.fillMaxSize())
        AnimatedContent(screen, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "screen") { s ->
            when (s) {
                Screen.Home -> Home(engine, state, open = { stack.add(it) })
                Screen.Library -> Library(engine, state, open = { stack.add(it) }, back = { stack.removeAt(stack.lastIndex) })
                Screen.Recent -> RecentPage(engine, state, open = { stack.add(it) }, back = { stack.removeAt(stack.lastIndex) })
                Screen.Listening -> ListeningPage(engine, state, open = { stack.add(it) }, back = { stack.removeAt(stack.lastIndex) })
                is Screen.Wrapped -> WrappedStory(engine, s.year, close = { stack.removeAt(stack.lastIndex) })
                is Screen.Playlist -> PlaylistPage(engine, state, s.id, open = { stack.add(it) }, back = { stack.removeAt(stack.lastIndex) })
                is Screen.Browse -> BrowsePage(state, s.kind, open = { stack.add(it) }, back = { stack.removeAt(stack.lastIndex) })
                Screen.Settings -> SettingsPage(engine, state, askFiles, back = { stack.removeAt(stack.lastIndex) })
            }
        }
        state.askSpeaker?.let { SpeakerQuestion(it, state) { yes -> engine.answerSpeaker(yes) } }
    }
}

// ---------------------------------------------------------------- the sky

@Composable
fun Starfield(modifier: Modifier) {
    val stars = remember {
        val r = Random(314)
        List(170) {
            val tint = when (r.nextInt(14)) { 0 -> Palette.Red; 1 -> Palette.Blue; else -> Palette.Starlight }
            Triple(Offset(r.nextFloat(), r.nextFloat()), r.nextFloat().let { it * it * 1.6f + 0.35f }, tint.copy(alpha = 0.12f + r.nextFloat() * 0.5f))
        }
    }
    Canvas(modifier) {
        for ((p, size, color) in stars) drawCircle(color, size * density * 0.7f, Offset(p.x * this.size.width, p.y * this.size.height))
    }
}

private fun reducedMotion(context: android.content.Context) =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Wavefronts from a source in motion: crowded ahead of it, stretched behind. */
@Composable
fun Wavefronts(color: Color, towardRight: Boolean, modifier: Modifier) {
    val still = reducedMotion(LocalContext.current)
    val phase = if (still) 0.35f else {
        val t = rememberInfiniteTransition(label = "waves")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "phase").value
    }
    Canvas(modifier) {
        val n = 6
        val base = size.minDimension * 0.18f
        val reach = size.minDimension * 0.62f
        val shift = size.minDimension * 0.34f * (if (towardRight) 1f else -1f)
        for (i in 0 until n) {
            val p = ((i.toFloat() / n) + phase) % 1f
            val radius = base + p * reach
            val center = Offset(size.width / 2 - shift * p, size.height / 2)
            drawCircle(color.copy(alpha = (1f - p) * 0.55f), radius, center, style = Stroke(width = 1.3.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun Home(engine: Engine, state: UiState, open: (Screen) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
        TopLine(engine, state, open)
        state.elsewhere?.takeIf { it.playing }?.let { Elsewhere(engine, state, it) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val h = maxHeight
            Column(Modifier.fillMaxSize()) {
                SlotBlock(engine, state, "1", Modifier.weight(1f), open)
                SlotBlock(engine, state, "2", Modifier.weight(1f), open)
            }
            if (h < 10.dp) Spacer(Modifier)
        }
        NowPlaying(engine, state, open)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("All music") { open(Screen.Library) }
            Pill("Recent") { open(Screen.Recent) }
            Spacer(Modifier.weight(1f))
            Text(state.output?.let { "To $it" } ?: if (state.onSpeaker) "Phone speaker" else "Connected",
                color = if (state.onSpeaker) Palette.Faint else Palette.Dust, size = 13)
        }
    }
}

@Composable
private fun TopLine(engine: Engine, state: UiState, open: (Screen) -> Unit) {
    // In December and January the year's story waits up here.
    val season = remember { YearHeard.season(java.time.YearMonth.now()) }
    var months by remember { mutableStateOf(engine.months) }
    if (season != null) LaunchedEffect(Unit) { months = engine.loadMonths() }
    val story = season?.takeIf { y -> months.any { it.month.startsWith("$y-") && it.seconds > 0 } }
    val line = when {
        state.message.isNotEmpty() -> state.message
        state.hubUrl.isBlank() -> "Not synced: add your hub in Settings"
        !state.onWifi && state.mobileUsed >= Net.BUDGET -> "Mobile data used up. Syncs on Wi-Fi"
        !state.onWifi -> "Off Wi-Fi: songs on the phone only"
        !state.hubOnline -> "Offline. Picks up the sync when the hub is back"
        else -> ""
    }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        story?.let { Box(Modifier.padding(end = 10.dp)) { Pill("Your $it", strong = true) { open(Screen.Wrapped(it)) } } }
        Text(line, Modifier.weight(1f), color = Palette.Dust, size = 13, maxLines = 1)
        Box(Modifier.offset(x = 10.dp)) { IconButton("Settings", { open(Screen.Settings) }) { c -> drawGear(c) } }
    }
}

@Composable
private fun Elsewhere(engine: Engine, state: UiState, other: Elsewhere) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(18.dp)).background(Palette.Panel)
        .border(1.dp, Palette.Hairline, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${other.device} is playing", color = Palette.Dust, size = 12)
            Text(other.track?.title ?: other.name, color = Palette.Starlight, size = 16, maxLines = 1)
        }
        Text("Play here", Modifier.clip(RoundedCornerShape(50)).clickable { engine.requestPlay(other.playlist) }
            .border(1.dp, Palette.Faint, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
            color = Palette.Starlight, size = 14, weight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotBlock(engine: Engine, state: UiState, slot: String, modifier: Modifier, open: (Screen) -> Unit) {
    val id = state.slots[slot]
    val view = state.playlists.firstOrNull { it.id == id }
    val lit = state.playing && state.playlist == id && id != null
    val color = Palette.slot(slot)
    val right = slot == "2"
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .combinedClickable(onClick = { engine.slot(slot) }, onLongClick = { id?.let { open(Screen.Playlist(it)) } })
        .semantics { contentDescription = if (lit) "Pause ${view?.name ?: slot}" else "Play ${view?.name ?: slot}" }) {
        val numeral = (maxHeight.value * 0.92f).coerceAtMost(maxWidth.value * 0.62f)
        if (lit) Wavefronts(color, towardRight = !right, Modifier.size((numeral * 1.25f).dp).align(if (right) Alignment.CenterEnd else Alignment.CenterStart))
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (right) Arrangement.End else Arrangement.Start) {
            val openIt = { id?.let { open(Screen.Playlist(it)) }; Unit }
            if (right) SlotWords(state, slot, view, lit, color, TextAlign.End, Modifier.weight(1f).padding(end = 6.dp), openIt)
            DotNumeral(slot, (numeral * 0.78f).dp, if (lit) color else Palette.Starlight.copy(alpha = if (state.playing) 0.42f else 0.88f),
                lit, animate = !reducedMotion(LocalContext.current), modifier = Modifier.padding(horizontal = 10.dp))
            if (!right) SlotWords(state, slot, view, lit, color, TextAlign.Start, Modifier.weight(1f).padding(start = 6.dp), openIt)
        }
    }
}

@Composable
private fun SlotWords(state: UiState, slot: String, view: PlaylistView?, lit: Boolean, color: Color, align: TextAlign, modifier: Modifier,
                      openIt: () -> Unit) {
    // The numeral plays; the words open the playlist (and so does a long-press anywhere).
    val end = align == TextAlign.End
    Box(modifier, contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = view != null, onClick = openIt)
        .semantics { contentDescription = "Open ${view?.name ?: slot}" }.padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when { view == null -> "Not set"; view.name == slot -> "${view.count} tracks"; else -> view.name },
                Modifier.weight(1f, fill = false), color = Palette.Starlight, size = 26, family = Type.Display, align = align, maxLines = 1)
            if (view != null) Canvas(Modifier.padding(start = 8.dp, top = 3.dp).size(8.dp, 13.dp)) { drawChevron(Palette.Dust) }
        }
        val note = when {
            view == null -> "Choose one in All music"
            lit -> "Playing"
            state.starting && state.pending == view.id -> "Starting"
            view.lastTrack == null -> "Shuffled, from the top"
            view.lastPlaying && view.lastDevice != state.device -> "Playing on ${view.lastDevice}"
            view.lastDevice != null && view.lastDevice != state.device -> "Left off on ${view.lastDevice}"
            else -> "Picks up where you left off"
        }
        Text(note, color = if (lit) color else Palette.Dust, size = 13, align = align, maxLines = 1)
        view?.lastTrack?.takeIf { !lit }?.let { Text(it.title, color = Palette.Faint, size = 13, align = align, maxLines = 1) }
    }
    }
}

@Composable
private fun NowPlaying(engine: Engine, state: UiState, open: (Screen) -> Unit) {
    val track = state.track ?: return
    var position by remember { mutableLongStateOf(engine.position()) }
    var duration by remember { mutableLongStateOf(engine.duration()) }
    LaunchedEffect(state.playing, track.id) {
        while (true) {
            position = engine.position()
            duration = engine.duration()
            if (!state.playing) break
            delay(250)
        }
    }
    val color = state.slots.entries.firstOrNull { it.value == state.playlist }?.key?.let { Palette.slot(it) } ?: Palette.Starlight
    var acting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The song and where it's from open that playlist, at the song.
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = state.playlist != null) { state.playlist?.let { open(Screen.Playlist(it)) } }
                .semantics { contentDescription = "${track.title}. Open ${state.playlistName.ifBlank { "the playlist" }}" }) {
                Text(track.title, color = Palette.Starlight, size = 24, family = Type.Display, maxLines = 1)
                Text(listOf(track.artist, state.playlistName).filter { it.isNotEmpty() }.joinToString(", from "), color = Palette.Dust, size = 14, maxLines = 1)
            }
            IconButton("More for ${track.title}", { acting = true }, tint = Palette.Dust) { c -> drawMore(c) }
        }
        SeekBar(position, duration, color, Modifier.fillMaxWidth().height(40.dp).padding(top = 10.dp)) { engine.seek(it) }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(clock(position), color = Palette.Faint, size = 12)
            Spacer(Modifier.weight(1f))
            Glyph("Previous", { engine.previous() }) { drawPrev(it) }
            Spacer(Modifier.width(18.dp))
            Glyph(if (state.playing) "Pause" else "Play", { engine.toggle() }, big = true) { if (state.playing) drawPause(it) else drawPlay(it) }
            Spacer(Modifier.width(18.dp))
            Glyph("Next", { engine.next() }) { drawNext(it) }
            Spacer(Modifier.weight(1f))
            Text(clock(duration), color = Palette.Faint, size = 12)
        }
    }
    if (acting) TrackMenu(engine, state, track, state.playlist, open) { acting = false }
}

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Where the song is, in the playing key's colour. Drag or tap anywhere on it to move. */
@Composable
fun SeekBar(position: Long, duration: Long, accent: Color, modifier: Modifier, seek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Canvas(modifier
        .pointerInput(duration) {
            detectTapGestures { o -> if (duration > 0) seek((o.x / size.width * duration).toLong()) }
        }
        .pointerInput(duration) {
            detectHorizontalDragGestures(
                onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { dragging?.let { if (duration > 0) seek((it * duration).toLong()) }; dragging = null },
                onDragCancel = { dragging = null },
            ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
        }
        .semantics { contentDescription = "Position ${clock(position)} of ${clock(duration)}" }) {
        val mid = size.height / 2
        val band = 4.dp.toPx()
        val played = size.width * fraction
        drawLine(Palette.Hairline, Offset(band / 2, mid), Offset(size.width - band / 2, mid), band, StrokeCap.Round)
        if (played > 0) drawLine(accent, Offset(band / 2, mid), Offset(played.coerceAtLeast(band / 2), mid), band, StrokeCap.Round)
        drawCircle(accent.copy(alpha = 0.22f), (if (dragging != null) 13 else 10).dp.toPx(), Offset(played, mid))
        drawCircle(accent, 5.5.dp.toPx(), Offset(played, mid))
    }
}

@Composable
private fun Glyph(label: String, onClick: () -> Unit, big: Boolean = false, draw: DrawScope.(Color) -> Unit) {
    val s = if (big) 56.dp else 44.dp
    Box(Modifier.size(s).clip(CircleShape).then(if (big) Modifier.border(1.dp, Palette.Faint, CircleShape) else Modifier)
        .clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(if (big) 18.dp else 16.dp)) { draw(Palette.Starlight) }
    }
}

private fun DrawScope.drawPlay(c: Color) {
    val p = Path().apply { moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.15f, size.height); close() }
    drawPath(p, c)
}

private fun DrawScope.drawPause(c: Color) {
    val w = size.width * 0.28f
    drawRect(c, Offset(size.width * 0.1f, 0f), androidx.compose.ui.geometry.Size(w, size.height))
    drawRect(c, Offset(size.width * 0.9f - w, 0f), androidx.compose.ui.geometry.Size(w, size.height))
}

private fun DrawScope.drawNext(c: Color) {
    val p = Path().apply { moveTo(0f, 0f); lineTo(size.width * 0.75f, size.height / 2); lineTo(0f, size.height); close() }
    drawPath(p, c)
    drawLine(c, Offset(size.width * 0.9f, 0f), Offset(size.width * 0.9f, size.height), size.width * 0.14f, StrokeCap.Round)
}

private fun DrawScope.drawPrev(c: Color) {
    val p = Path().apply { moveTo(size.width, 0f); lineTo(size.width * 0.25f, size.height / 2); lineTo(size.width, size.height); close() }
    drawPath(p, c)
    drawLine(c, Offset(size.width * 0.1f, 0f), Offset(size.width * 0.1f, size.height), size.width * 0.14f, StrokeCap.Round)
}

// ---------------------------------------------------------------- the library (the tucked-away part)

@Composable
private fun Library(engine: Engine, state: UiState, open: (Screen) -> Unit, back: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var naming by remember { mutableStateOf(false) }
    val q = query.trim().lowercase()
    val matches = remember(q, state.libraryCount) {
        if (q.length < 2) emptyList() else engine.library.values.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }.take(80)
    }
    val named = remember(q, state.playlists) {
        if (q.length < 2) emptyList() else state.playlists.filter {
            (it.kind == "artist" || it.kind == "album") && (it.name.lowercase().contains(q) || it.by.lowercase().contains(q))
        }
    }
    val artists = named.filter { it.kind == "artist" }.take(4)
    val albums = named.filter { it.kind == "album" }.take(6)
    var acting by remember { mutableStateOf<Track?>(null) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 22.dp)) {
        item { Header("All music", back) }
        item {
            val sub = if (state.libraryCount == 0) "Nothing yet: the library comes from the hub"
            else "${state.libraryCount} tracks, ${state.localCount} of them on this phone"
            Text(sub, color = Palette.Dust, size = 14, modifier = Modifier.padding(bottom = 16.dp))
            Field(query, { query = it }, "Find a song, artist or album")
            if (q.length < 2 && state.libraryCount > 0) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Artists") { open(Screen.Browse("artist")) }
                Pill("Albums") { open(Screen.Browse("album")) }
            }
        }
        if (q.length >= 2) {
            if (matches.isEmpty() && named.isEmpty()) item { Text("Nothing matches that", color = Palette.Dust, size = 15, modifier = Modifier.padding(vertical = 14.dp)) }
            if (artists.isNotEmpty()) item { Section("Artists") }
            items(artists, key = { "a" + it.id }) { p -> PlaylistRow(p, state) { open(Screen.Playlist(p.id)) } }
            if (albums.isNotEmpty()) item { Section("Albums") }
            items(albums, key = { "b" + it.id }) { p -> PlaylistRow(p, state) { open(Screen.Playlist(p.id)) } }
            if (named.isNotEmpty() && matches.isNotEmpty()) item { Section("Songs") }
            items(matches, key = { "m" + it.id }) { t ->
                TrackRow(engine, t, state.track?.id == t.id, selected = false, onClick = {
                    engine.requestPlay("f:" + t.id.substringBeforeLast("/", ""), t.id)
                }, onLong = {}, onMore = { acting = t })
            }
        } else {
            item { Section("Folders") }
            items(state.playlists.filter { it.kind == "folder" }, key = { it.id }) { p -> PlaylistRow(p, state) { open(Screen.Playlist(p.id)) } }
            item { Section("Your playlists") }
            items(state.playlists.filter { it.custom }, key = { it.id }) { p -> PlaylistRow(p, state) { open(Screen.Playlist(p.id)) } }
            item {
                Text("New playlist", Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { naming = true }.padding(vertical = 14.dp),
                    color = Palette.Starlight, size = 16, weight = FontWeight.Medium)
            }
            item { Section("This phone") }
            item {
                Text("Settings", Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { open(Screen.Settings) }.padding(vertical = 14.dp),
                    color = Palette.Starlight, size = 16)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (naming) NameDialog("New playlist", "", "Make it", { naming = false }) { name ->
        naming = false
        engine.hubEdit("POST", "/api/playlists", JSONObject().put("name", name).put("tracks", JSONArray())) { r ->
            r?.optJSONObject("playlist")?.optString("id")?.let { open(Screen.Playlist(it)) }
        }
    }
    acting?.let { TrackMenu(engine, state, it, null, open) { acting = null } }
}

/** Playlists and songs played lately, newest first. The ones on a key are left out of the playlists:
 * they're on the home screen already. */
@Composable
private fun RecentPage(engine: Engine, state: UiState, open: (Screen) -> Unit, back: () -> Unit) {
    val lists = remember(state.playlists) { state.playlists.filter { it.updated > 0 && it.slot.isEmpty() }.sortedByDescending { it.updated }.take(6) }
    val songs = remember(state.playsRev, state.libraryCount) { engine.recent() }
    var acting by remember { mutableStateOf<Track?>(null) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
        item { Header("Recent", back) }
        item {
            // The way in to Listening, with this month's time so far.
            val month = remember(state.playsRev) { java.time.YearMonth.now().toString() }
            var months by remember { mutableStateOf(engine.months) }
            LaunchedEffect(state.playsRev) { months = engine.loadMonths() }
            val heard = months.firstOrNull { it.month == month }?.seconds ?: 0L
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { open(Screen.Listening) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Listening", Modifier.weight(1f), color = Palette.Starlight, size = 18, family = Type.Display)
                if (heard > 0) Text(hoursMinutes(heard) + " this month", color = Palette.Faint, size = 13)
            }
        }
        if (lists.isEmpty() && songs.isEmpty()) item {
            Text("Nothing played yet", color = Palette.Dust, size = 15, modifier = Modifier.padding(vertical = 14.dp))
        }
        if (lists.isNotEmpty()) item { Section("Playlists") }
        items(lists, key = { "p" + it.id }) { p -> PlaylistRow(p, state) { open(Screen.Playlist(p.id)) } }
        if (songs.isNotEmpty()) item { Section("Songs") }
        items(songs, key = { "s" + it.id }) { t ->
            TrackRow(engine, t, state.track?.id == t.id, selected = false, note = ago(engine.playsOf(t.id).last),
                onClick = { engine.requestPlay("f:" + t.id.substringBeforeLast("/", ""), t.id) }, onLong = {}, onMore = { acting = t })
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
    acting?.let { TrackMenu(engine, state, it, null, open) { acting = null } }
}

/** How much was heard in a month, everywhere together: the hours drawn in stars, the months before it
 * as columns of stars (tap one to see it), and who and what was heard most. */
@Composable
private fun ListeningPage(engine: Engine, state: UiState, open: (Screen) -> Unit, back: () -> Unit) {
    var months by remember { mutableStateOf(engine.months) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(state.playsRev) { months = engine.loadMonths(); loaded = true }
    val now = remember { java.time.YearMonth.now() }
    val keys = remember(now) { (11 downTo 0).map { now.minusMonths(it.toLong()) } }
    var picked by rememberSaveable { mutableStateOf(now.toString()) }
    val m = months.firstOrNull { it.month == picked } ?: MonthHeard(picked, 0, 0, 0, emptyList(), emptyList())
    val pickedMonth = java.time.YearMonth.parse(picked)
    val monthName = pickedMonth.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale.getDefault())
    var acting by remember { mutableStateOf<Track?>(null) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
        item { Header("Listening", back) }
        item {
            val inHours = m.seconds >= 3600
            val amount = (m.seconds / if (inHours) 3600 else 60).toString()
            val unit = if (inHours) (if (amount == "1") "hour" else "hours") else (if (amount == "1") "minute" else "minutes")
            Row(Modifier.padding(top = 14.dp).semantics { contentDescription = "$amount $unit" }, verticalAlignment = Alignment.Bottom) {
                for (d in amount) DotNumeral(d.toString(), 84.dp, Palette.Starlight, lit = false, animate = false, modifier = Modifier.padding(end = 12.dp))
                Text(unit, color = Palette.Dust, size = 17, modifier = Modifier.padding(start = 10.dp))
            }
            val parts = listOfNotNull("$monthName ${pickedMonth.year}",
                m.plays.takeIf { it > 0 }?.let { if (it == 1) "1 play" else "$it plays" },
                m.songs.takeIf { it > 0 }?.let { if (it == 1) "1 song" else "$it songs" })
            Text(parts.joinToString(", "), color = Palette.Dust, size = 13, modifier = Modifier.padding(top = 14.dp))
            YearHeard.storyFor(pickedMonth, now, months)?.let { year ->
                Box(Modifier.padding(top = 14.dp)) { Pill("Your $year", strong = true) { open(Screen.Wrapped(year)) } }
            }
        }
        item {
            val values = keys.map { k -> months.firstOrNull { it.month == k.toString() }?.seconds ?: 0L }
            MonthStars(keys.map { it.toString() }, values, picked, Modifier.padding(top = 24.dp)) { picked = it }
        }
        if (m.artists.isNotEmpty()) item { Section("Artists") }
        items(m.artists, key = { "a" + it.first }) { (name, seconds) ->
            val artist = state.playlists.firstOrNull { it.kind == "artist" && it.name.equals(name, true) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .then(if (artist != null) Modifier.clickable { open(Screen.Playlist(artist.id)) } else Modifier)
                .padding(start = 4.dp, top = 11.dp, bottom = 11.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), color = Palette.Starlight, size = 16, maxLines = 1)
                Text(hoursMinutes(seconds), color = Palette.Faint, size = 13)
            }
        }
        if (m.top.isNotEmpty()) item { Section("Songs") }
        items(m.top, key = { "s" + it.first }) { (id, seconds, _) ->
            val t = engine.track(id) ?: return@items
            TrackRow(engine, t, state.track?.id == t.id, selected = false, note = hoursMinutes(seconds),
                onClick = { engine.requestPlay("f:" + t.id.substringBeforeLast("/", ""), t.id) }, onLong = {}, onMore = { acting = t })
        }
        if (loaded && m.seconds == 0L) item {
            Text("Nothing heard in $monthName", color = Palette.Dust, size = 15, modifier = Modifier.padding(vertical = 26.dp))
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
    acting?.let { TrackMenu(engine, state, it, null, open) { acting = null } }
}

/** Twelve months as columns of stars, the tallest month nine stars high. Tap a column to pick it. */
@Composable
private fun MonthStars(keys: List<String>, values: List<Long>, picked: String, modifier: Modifier, height: Dp = 112.dp,
                       tappable: Boolean = true, pick: (String) -> Unit) {
    val rows = 9
    val top = values.maxOrNull() ?: 0L
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height).then(if (!tappable) Modifier else Modifier.pointerInput(keys) {
            detectTapGestures { p -> pick(keys[(p.x / (size.width / keys.size)).toInt().coerceIn(0, keys.lastIndex)]) }
        }).semantics { contentDescription = "Listening by month" }) {
            val col = size.width / keys.size
            val pitch = minOf(col * 0.5f, size.height / rows)
            keys.forEachIndexed { i, key ->
                val v = values[i]
                val lit = if (v > 0 && top > 0) maxOf(1, Math.round(v.toFloat() / top * rows)) else 0
                val on = key == picked
                val x = (i + 0.5f) * col
                for (r in 0 until rows) {
                    val center = Offset(x, size.height - (r + 0.5f) * pitch)
                    if (r < lit) drawCircle(if (on) Palette.Starlight else Palette.Dust.copy(alpha = 0.55f), pitch * 0.33f, center)
                    else drawCircle(Palette.Faint.copy(alpha = 0.45f), pitch * 0.07f, center)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for (key in keys) {
                val letter = java.time.YearMonth.parse(key).month.getDisplayName(java.time.format.TextStyle.NARROW_STANDALONE, java.util.Locale.getDefault())
                Text(letter, Modifier.weight(1f).then(if (tappable) Modifier.clickable { pick(key) } else Modifier), color = if (key == picked) Palette.Starlight else Palette.Faint,
                    size = 12, align = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** The year in music, a page at a time: tap the right of the screen for the next, the left for the one
 * before. The year, how long, the biggest month, the top artist and song, how many songs, then all of it. */
@Composable
private fun WrappedStory(engine: Engine, year: Int, close: () -> Unit) {
    var heard by remember { mutableStateOf<YearHeard?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(year) { heard = engine.loadYear(year); loaded = true }
    val y = heard
    val pages = if (y != null && y.seconds > 0) 7 else 1
    var page by rememberSaveable { mutableStateOf(0) }
    val animate = !reducedMotion(LocalContext.current)
    fun step(by: Int) { val p = page + by; if (p >= pages) close() else if (p >= 0) page = p }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Void).pointerInput(pages) {
        detectTapGestures { p -> step(if (p.x < size.width / 3) -1 else 1) }
    }.statusBarsPadding().navigationBarsPadding()) {
        val room = maxWidth - 56.dp
        Row(Modifier.align(Alignment.TopCenter).padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in 0 until pages) Box(Modifier.size(6.dp).clip(CircleShape)
                .background(if (i == page) Palette.Starlight else if (i < page) Palette.Dust else Palette.Faint))
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp)) { IconButton("Close", close) { c -> drawClose(c) } }
        AnimatedContent(page, Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 64.dp),
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(120)) }, label = "story") { at ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                if (y == null || y.seconds == 0L || at == 0) {
                    DotNumber(year.toString(), 120.dp, room, animate)
                    val words = when {
                        !loaded -> ""
                        y == null -> "Connect to Wi-Fi to see it"
                        y.seconds == 0L -> "Nothing heard in $year"
                        else -> "Your year in music"
                    }
                    Text(words, Modifier.padding(top = 26.dp), color = if (y != null && y.seconds > 0) Palette.Starlight else Palette.Dust,
                        size = 32, family = Type.Display)
                } else when (at) {
                    1 -> {
                        val minutes = y.seconds / 60
                        val hours = y.seconds / 3600.0
                        Text("You listened for", color = Palette.Dust, size = 20)
                        DotNumber(minutes.toString(), 120.dp, room, animate, Modifier.padding(vertical = 22.dp))
                        Text(if (minutes == 1L) "minute" else "minutes", color = Palette.Dust, size = 20)
                        val days = when {
                            hours >= 48 -> "That's ${Math.round(hours / 24)} days of music"
                            hours >= 2 -> "That's ${Math.round(hours)} hours of music"
                            else -> ""
                        }
                        if (days.isNotEmpty()) Text(days, Modifier.padding(top = 18.dp), size = 20)
                    }
                    2 -> {
                        Text("Your biggest month", color = Palette.Dust, size = 20)
                        Text(monthName(y.best), Modifier.padding(top = 4.dp), size = 48, family = Type.Display, maxLines = 1)
                        Text(hoursMinutes(y.months[y.best]), color = Palette.Dust, size = 18)
                        val keys = (1..12).map { "%04d-%02d".format(year, it) }
                        MonthStars(keys, y.months, keys[y.best], Modifier.padding(top = 34.dp), height = 150.dp, tappable = false) {}
                    }
                    3 -> {
                        Text("Your top artist", color = Palette.Dust, size = 20)
                        Text(y.artists.firstOrNull()?.first ?: "", Modifier.padding(vertical = 6.dp), size = 46, family = Type.Display, maxLines = 3)
                        Text(hoursMinutes(y.artists.firstOrNull()?.second ?: 0), color = Palette.Dust, size = 18)
                        Spacer(Modifier.height(28.dp))
                        y.artists.drop(1).forEachIndexed { i, (name, seconds) -> Ranked(i + 2, name, hoursMinutes(seconds)) }
                    }
                    4 -> {
                        val first = y.top.firstOrNull()
                        val t = engine.track(first?.first)
                        Text("Your top song", color = Palette.Dust, size = 20)
                        Text(t?.title ?: "", Modifier.padding(top = 6.dp), size = 42, family = Type.Display, maxLines = 3)
                        Text(t?.artist ?: "", Modifier.padding(top = 4.dp, bottom = 4.dp), size = 20, maxLines = 2)
                        first?.let { (_, seconds, plays) ->
                            Text((if (plays == 1) "1 play" else "$plays plays") + ", " + hoursMinutes(seconds), color = Palette.Dust, size = 17)
                        }
                        Spacer(Modifier.height(28.dp))
                        y.top.drop(1).forEachIndexed { i, (id, seconds, _) ->
                            val s = engine.track(id) ?: return@forEachIndexed
                            Ranked(i + 2, s.title + if (s.artist.isNotBlank()) ", " + s.artist else "", hoursMinutes(seconds))
                        }
                    }
                    5 -> {
                        Text("You heard", color = Palette.Dust, size = 20)
                        DotNumber(y.songs.toString(), 120.dp, room, animate, Modifier.padding(vertical = 22.dp))
                        Text(if (y.songs == 1) "song" else "different songs", color = Palette.Dust, size = 20)
                        Text("by ${y.artistCount} " + if (y.artistCount == 1) "artist" else "artists", Modifier.padding(top = 18.dp), size = 20)
                    }
                    else -> {
                        DotNumber(year.toString(), 44.dp, room, animate, Modifier.padding(bottom = 28.dp))
                        Text("Top artists", color = Palette.Dust, size = 14, modifier = Modifier.padding(bottom = 6.dp))
                        y.artists.forEachIndexed { i, (name, _) -> Ranked(i + 1, name, null, size = 16) }
                        Text("Top songs", color = Palette.Dust, size = 14, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                        y.top.forEachIndexed { i, (id, _, _) -> engine.track(id)?.let { Ranked(i + 1, it.title, null, size = 16) } }
                        Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Stat("Minutes", "%,d".format(y.seconds / 60))
                            Stat("Songs", "%,d".format(y.songs))
                            Stat("Top month", monthName(y.best))
                        }
                    }
                }
            }
        }
    }
}

/** A number in star numerals, as big as fits in [room]. */
@Composable
private fun DotNumber(value: String, size: Dp, room: Dp, animate: Boolean, modifier: Modifier = Modifier) {
    val fitted = minOf(size, room / (value.length * (5f / 7f + 0.16f)))
    Row(modifier.semantics { contentDescription = value }, horizontalArrangement = Arrangement.spacedBy(fitted * 0.16f)) {
        for (d in value) DotNumeral(d.toString(), fitted, Palette.Starlight, lit = true, animate = animate)
    }
}

@Composable
private fun Ranked(rank: Int, name: String, note: String?, size: Int = 17) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(rank.toString(), Modifier.width(26.dp), color = Palette.Faint, size = size)
        Text(name, Modifier.weight(1f, fill = false), size = size, maxLines = 1)
        note?.let { Text(it, Modifier.padding(start = 12.dp), color = Palette.Faint, size = size - 3, maxLines = 1) }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, color = Palette.Dust, size = 13)
        Text(value, size = 26, family = Type.Display, maxLines = 1)
    }
}

private fun monthName(index: Int): String =
    java.time.Month.of(index + 1).getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale.getDefault())

/** "6 h 05 m", or "45 m" under an hour. */
private fun hoursMinutes(seconds: Long): String {
    val m = seconds / 60
    return if (m >= 60) "%d h %02d m".format(m / 60, m % 60) else "$m m"
}

/** Everything you can do to one song: add it to a playlist, take it out of this one, go to its
 * artist or album, or delete it from the library. */
@Composable
private fun TrackMenu(engine: Engine, state: UiState, t: Track, playlist: String?, open: (Screen) -> Unit, dismiss: () -> Unit) {
    var step by remember(t.id) { mutableStateOf("menu") }
    val view = state.playlists.firstOrNull { it.id == playlist }
    when (step) {
        "add" -> AddDialog(engine, state, listOf(t.id), dismiss, dismiss)
        "delete" -> Ask("Delete ${t.title}?", "The file goes to the hub's trash, and off every device.", "Delete", dismiss) {
            engine.deleteTracks(listOf(t.id))
            dismiss()
        }
        else -> Sheet(dismiss) {
            Text(t.title, color = Palette.Starlight, size = 22, family = Type.Display, maxLines = 2)
            val sub = listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(", ")
            if (sub.isNotEmpty()) Text(sub, color = Palette.Dust, size = 14, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            SheetItem("Add to playlist") { step = "add" }
            if (view?.custom == true) SheetItem("Remove from ${view.name}") {
                engine.hubEdit("PUT", "/api/playlists/${view.id}", JSONObject().put("remove", JSONArray(listOf(t.id))))
                dismiss()
            }
            val artist = state.playlists.firstOrNull { it.kind == "artist" && it.name.equals(t.artist, true) && it.id != playlist }
            val album = state.playlists.firstOrNull { it.kind == "album" && it.name.equals(t.album, true) && it.id != playlist &&
                (it.by.isEmpty() || it.by.equals(t.albumArtist.ifEmpty { t.artist }, true)) }
            artist?.let { a -> SheetItem("Go to ${a.name}") { dismiss(); open(Screen.Playlist(a.id)) } }
            album?.let { a -> SheetItem("Go to the album") { dismiss(); open(Screen.Playlist(a.id)) } }
            SheetItem("Delete from library", Palette.Red) { step = "delete" }
        }
    }
}

/** Every artist, or every album, in the library, with a search through them. */
@Composable
private fun BrowsePage(state: UiState, kind: String, open: (Screen) -> Unit, back: () -> Unit) {
    val all = remember(state.playlists, kind) { state.playlists.filter { it.kind == kind } }
    var find by rememberSaveable(kind) { mutableStateOf("") }
    var searching by rememberSaveable(kind) { mutableStateOf(find.isNotEmpty()) }
    val q = find.trim().lowercase()
    val shown = remember(all, q) { if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) || it.by.lowercase().contains(q) } }
    val noun = if (kind == "artist") "artist" else "album"
    BackHandler(searching) { searching = false; find = "" }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 22.dp)) {
        // Outside the list, so the search field stays put (and keeps focus) while the results scroll.
        TopBar(back, searching, find, { find = it }, "Find an $noun",
            openSearch = if (all.size > 1) ({ searching = true }) else null,
            closeSearch = { searching = false; find = "" })
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Title(if (kind == "artist") "Artists" else "Albums")
                Text(when {
                    all.isEmpty() -> "None yet: they come from the songs' tags"
                    q.isEmpty() -> "${all.size}"
                    else -> "${shown.size} of ${all.size}"
                }, color = Palette.Dust, size = 14, modifier = Modifier.padding(bottom = 10.dp))
            }
            if (all.isNotEmpty() && shown.isEmpty()) item {
                Text("No $noun matches that", color = Palette.Dust, size = 15, modifier = Modifier.padding(vertical = 14.dp))
            }
            items(shown, key = { it.id }) { p -> PlaylistRow(p, state) { open(Screen.Playlist(p.id)) } }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun PlaylistRow(p: PlaylistView, state: UiState, onClick: () -> Unit) {
    val depth = if (p.kind != "folder" || p.path.isEmpty()) 0 else p.path.count { it == '/' }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
        .padding(start = (depth * 18).dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.name, color = Palette.Starlight, size = if (depth == 0) 18 else 16, family = if (depth == 0) Type.Display else Type.Sans, maxLines = 1)
            val playingHere = state.playing && state.playlist == p.id
            Text(if (playingHere) "Playing" else (if (p.by.isNotEmpty()) "${p.by}, " else "") + (if (p.count == 1) "1 track" else "${p.count} tracks") + if (p.mode == "order") ", in order" else "",
                color = if (playingHere) Palette.slot(p.slot.ifEmpty { "1" }) else Palette.Faint, size = 12)
        }
        if (p.slot.isNotEmpty()) SlotBadge(p.slot)
    }
}

@Composable
private fun SlotBadge(slot: String) {
    Box(Modifier.size(30.dp).border(1.dp, Palette.slot(slot), CircleShape), contentAlignment = Alignment.Center) {
        DotNumeral(slot, 13.dp, Palette.slot(slot), lit = false, animate = false)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistPage(engine: Engine, state: UiState, id: String, open: (Screen) -> Unit, back: () -> Unit) {
    val view = state.playlists.firstOrNull { it.id == id }
    val tracks = remember(id, state.libraryCount, state.customs) { engine.tracksOf(id).map { engine.track(it)!! } }
    var find by rememberSaveable(id) { mutableStateOf("") }
    var sort by remember { mutableStateOf(engine.store.sort) }
    var choosingSort by remember { mutableStateOf(false) }
    val q = find.trim().lowercase()
    val next = remember(id, state.playlist, state.index, state.count) { engine.upNext(id) }
    val sorts = if (next != null) SORTS else SORTS - "next"
    val sorting = sort.takeIf { it in sorts } ?: "default"
    val shown = remember(tracks, q, sorting, state.playsRev, next) {
        sortTracks(engine, tracks.filter { q.isEmpty() || it.matches(q) }, sorting, next)
    }
    val selected = remember(id) { mutableStateListOf<String>() }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deletingSongs by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<Track?>(null) }
    var searching by rememberSaveable(id) { mutableStateOf(find.isNotEmpty()) }
    var more by remember { mutableStateOf(false) }
    var picking by rememberSaveable(id) { mutableStateOf(false) }
    val pickMode = picking || selected.isNotEmpty()
    val custom = view?.custom == true
    BackHandler(searching) { searching = false; find = "" }
    BackHandler(pickMode) { selected.clear(); picking = false }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 22.dp)) {
        // Outside the list, so the search field stays put (and keeps focus) while the results scroll.
        TopBar(back, searching, find, { find = it }, "Find in ${view?.name ?: "this playlist"}",
            openSearch = if (tracks.size > 1) ({ searching = true }) else null,
            closeSearch = { searching = false; find = "" },
            sortOn = sorting != "default", openSort = if (tracks.size > 1) ({ choosingSort = true }) else null,
            openMore = { more = true })
        // Opened on the playlist that's playing, the list starts at the song (the header is the first item).
        val list = rememberLazyListState()
        LaunchedEffect(id) {
            val at = shown.indexOfFirst { it.id == state.track?.id }
            if (state.playlist == id && at > 2) list.scrollToItem(at)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            item {
                Title(view?.name ?: "Playlist")
                val about = buildString {
                    append(if (tracks.size == 1) "1 track" else "${tracks.size} tracks")
                    if (view?.mode == "order") append(", in order")
                    if (custom) append(", made by you")
                    else if (view != null && view.by.isNotEmpty()) append(", by ${view.by}")
                    else if (view != null && view.path.isNotEmpty() && view.path != view.name) append(", from ${view.path}")
                }
                Text(about, color = Palette.Dust, size = 14)
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val here = state.playing && state.playlist == id
                    PlayButton(here, view?.slot?.takeIf { it.isNotEmpty() }?.let { Palette.slot(it) } ?: Palette.Starlight) {
                        if (here) engine.toggle() else engine.requestPlay(id)
                    }
                    Text(if (view?.mode == "order") "From the top" else "Reshuffle",
                        Modifier.padding(start = 10.dp).clip(RoundedCornerShape(12.dp)).clickable { engine.requestPlay(id, fresh = true) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        color = Palette.Starlight, size = 15, weight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    for (slot in listOf("1", "2")) KeyToggle(slot, view?.slot == slot) { engine.setSlot(slot, id) }
                }
            }
            if (tracks.isNotEmpty() && shown.isEmpty()) item {
                Text("Nothing here matches that", color = Palette.Dust, size = 15, modifier = Modifier.padding(vertical = 14.dp))
            }
            if (tracks.isEmpty()) item {
                Text(when (view?.kind) {
                    "custom" -> "Empty. Find songs in a folder, long-press them and add them here."
                    "folder", null -> "No music in this folder"
                    else -> "No music left here"
                },
                    color = Palette.Dust, size = 15, modifier = Modifier.padding(vertical = 20.dp))
            }
            items(shown, key = { it.id }) { t ->
                val picked = t.id in selected
                TrackRow(engine, t, state.track?.id == t.id && state.playlist == id, picked, note = sortNote(engine, t, sorting),
                    onClick = { if (pickMode) { if (picked) selected.remove(t.id) else selected.add(t.id) } else engine.requestPlay(id, t.id) },
                    onLong = { if (picked) selected.remove(t.id) else selected.add(t.id) },
                    onMore = if (pickMode) null else ({ acting = t }))
            }
            item { Spacer(Modifier.height(if (pickMode) 120.dp else 40.dp)) }
        }
        }
        if (pickMode) {
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Palette.Panel).border(1.dp, Palette.Hairline, RoundedCornerShape(22.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (selected.isEmpty()) "Tap songs to pick them" else "${selected.size} picked", color = Palette.Starlight, size = 15,
                    modifier = Modifier.weight(1f).padding(start = 6.dp))
                if (selected.isEmpty()) Pill("Cancel") { picking = false }
                else {
                    if (custom) Pill("Remove") {
                        engine.hubEdit("PUT", "/api/playlists/$id", JSONObject().put("remove", JSONArray(selected.toList())))
                        selected.clear(); picking = false
                    }
                    Pill("Delete", color = Palette.Red) { deletingSongs = true }
                    Pill("Add to playlist", true) { adding = true }
                }
            }
        }
    }
    if (choosingSort) Sheet({ choosingSort = false }) {
        Text("Sort by", color = Palette.Starlight, size = 22, family = Type.Display, modifier = Modifier.padding(bottom = 8.dp))
        for (key in sorts) {
            val on = key == sorting
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                sort = key
                engine.store.sort = key
                choosingSort = false
            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sortName(key, view?.kind), Modifier.weight(1f), color = if (on) Palette.Starlight else Palette.Dust, size = 16,
                    weight = if (on) FontWeight.Medium else FontWeight.Normal)
                if (on) Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.Starlight))
            }
        }
    }
    if (more) Sheet({ more = false }) {
        SheetItem(if (view?.mode == "order") "Shuffle it" else "Play in order") {
            more = false
            engine.setMode(id, if (view?.mode == "order") "shuffle" else "order")
        }
        if (tracks.isNotEmpty()) SheetItem("Pick songs") { more = false; picking = true }
        if (custom) {
            SheetItem("Rename") { more = false; renaming = true }
            SheetItem("Delete", Palette.Red) { more = false; confirmDelete = true }
        }
    }
    if (adding) AddDialog(engine, state, selected.toList(), { adding = false }) { adding = false; selected.clear(); picking = false }
    if (deletingSongs) Ask(if (selected.size == 1) "Delete 1 song?" else "Delete ${selected.size} songs?",
        "The files go to the hub's trash, and off every device.", "Delete", { deletingSongs = false }) {
        deletingSongs = false
        engine.deleteTracks(selected.toList())
        selected.clear(); picking = false
    }
    acting?.let { TrackMenu(engine, state, it, id, open) { acting = null } }
    if (renaming && view != null) NameDialog("Rename", view.name, "Rename", { renaming = false }) { name ->
        renaming = false
        engine.hubEdit("PUT", "/api/playlists/$id", JSONObject().put("name", name))
    }
    if (confirmDelete && view != null) Ask("Delete ${view.name}?", "The songs stay in your library; only this list goes.", "Delete", { confirmDelete = false }) {
        confirmDelete = false
        engine.hubEdit("DELETE", "/api/playlists/$id", null)
        back()
    }
}

private val SORTS = listOf("default", "next", "title", "artist", "album", "most", "least", "recent")

private fun sortName(key: String, kind: String?) = when (key) {
    "next" -> "Up next"; "title" -> "Title"; "artist" -> "Artist"; "album" -> "Album"
    "most" -> "Most played"; "least" -> "Least played"; "recent" -> "Recently played"
    else -> when (kind) { "custom" -> "Order added"; "artist", "album" -> "Album order"; else -> "File order" }
}

private fun Track.matches(q: String) = title.lowercase().contains(q) || artist.lowercase().contains(q) || album.lowercase().contains(q)

/** Blank names go last; ties keep the playlist's own order (sortedWith is stable). */
private fun sortTracks(engine: Engine, tracks: List<Track>, sort: String, next: List<String>?): List<Track> {
    fun text(f: (Track) -> String) = compareBy<Track> { f(it).isBlank() }.thenBy(Core.natural, f)
    return when (sort) {
        "next" -> next?.let { order ->
            val at = order.withIndex().associate { it.value to it.index }
            tracks.sortedBy { at[it.id] ?: Int.MAX_VALUE }
        } ?: tracks
        "title" -> tracks.sortedWith(text { it.title })
        "artist" -> tracks.sortedWith(text { it.artist }.then(text { it.album }))
        "album" -> tracks.sortedWith(text { it.album })
        "most" -> tracks.sortedByDescending { engine.playsOf(it.id).count }
        "least" -> tracks.sortedBy { engine.playsOf(it.id).count }
        "recent" -> tracks.sortedByDescending { engine.playsOf(it.id).last }
        else -> tracks
    }
}

private fun sortNote(engine: Engine, t: Track, sort: String): String? = when (sort) {
    "most", "least" -> engine.playsOf(t.id).count.let { if (it == 0) "Not played" else if (it == 1) "1 play" else "$it plays" }
    "recent" -> ago(engine.playsOf(t.id).last)
    else -> null
}

private fun ago(seconds: Double): String {
    if (seconds <= 0) return "Never"
    val days = ((System.currentTimeMillis() / 1000.0 - seconds) / 86400).toInt()
    return when {
        days < 1 -> "Today"
        days < 2 -> "Yesterday"
        days < 14 -> "$days days ago"
        days < 60 -> "${days / 7} weeks ago"
        days < 365 -> "${days / 30} months ago"
        days < 730 -> "A year ago"
        else -> "${days / 365} years ago"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(engine: Engine, t: Track, current: Boolean, selected: Boolean, note: String? = null, onClick: () -> Unit, onLong: () -> Unit,
                     onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Palette.Panel else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(start = 4.dp, top = 2.dp, bottom = 2.dp, end = if (onMore != null) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterStart) {
            if (current) Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.Starlight))
            else if (selected) Box(Modifier.size(7.dp).border(1.dp, Palette.Starlight, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Palette.Starlight, size = 16, maxLines = 1)
            val sub = listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(", ")
            if (sub.isNotEmpty()) Text(sub, color = Palette.Faint, size = 12, maxLines = 1)
        }
        if (!engine.isLocal(t.id)) Text("streams", color = Palette.Faint, size = 11, modifier = Modifier.padding(horizontal = 8.dp))
        if (note != null) Text(note, color = Palette.Faint, size = 12)
        else if (t.duration > 0) Text(clock((t.duration * 1000).toLong()), color = Palette.Faint, size = 12)
        if (onMore != null) IconButton("More for ${t.title}", onMore, tint = Palette.Dust) { c -> drawMore(c) }
        else Spacer(Modifier.height(44.dp))
    }
}

@Composable
private fun AddDialog(engine: Engine, state: UiState, tracks: List<String>, dismiss: () -> Unit, done: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameDialog("New playlist", "", "Make it", dismiss) { name ->
            engine.hubEdit("POST", "/api/playlists", JSONObject().put("name", name).put("tracks", JSONArray(tracks)))
            done()
        }
        return
    }
    Sheet(dismiss) {
        Text("Add ${tracks.size} to", color = Palette.Starlight, size = 22, family = Type.Display, modifier = Modifier.padding(bottom = 10.dp))
        for (c in state.customs) {
            Text(c.name, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                engine.hubEdit("PUT", "/api/playlists/${c.id}", JSONObject().put("add", JSONArray(tracks)))
                done()
            }.padding(vertical = 12.dp), color = Palette.Starlight, size = 16)
        }
        Text("A new playlist", Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { naming = true }.padding(vertical = 12.dp),
            color = Palette.Dust, size = 16, weight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsPage(engine: Engine, state: UiState, askFiles: () -> Unit, back: () -> Unit) {
    var hub by rememberSaveable { mutableStateOf(state.hubUrl) }
    var device by rememberSaveable { mutableStateOf(state.device) }
    val context = LocalContext.current
    val stops = remember(state.message) { engine.store.stops() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp)) {
        Header("Settings", back)
        Label("Hub address")
        Field(hub, { hub = it }, "https://doppler.example.ts.net")
        Text(if (state.hubOnline) "Connected" else if (state.hubUrl.isBlank()) "Not set" else "Can't reach it right now",
            color = Palette.Faint, size = 12, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        Label("This phone's name")
        Field(device, { device = it }, "phone")
        Text("Your other devices see this when the phone is playing", color = Palette.Faint, size = 12, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        Pill("Save", true) { engine.setHub(hub, device) }
        Spacer(Modifier.height(28.dp))
        Label("Music on this phone")
        if (LocalFiles.allowed(context)) {
            Text("${state.localCount} of ${state.libraryCount} tracks play from the phone itself; the rest stream from the hub on Wi-Fi.",
                color = Palette.Dust, size = 14)
        } else {
            Text("Everything streams from the hub. Let doppler see your music to play the copies already on the phone.",
                color = Palette.Dust, size = 14, modifier = Modifier.padding(bottom = 10.dp))
            Pill("Use music on this phone", true, onClick = askFiles)
        }
        Spacer(Modifier.height(28.dp))
        Label("Mobile data")
        Text("Only your place in the song is sent. ${"%.1f".format(state.mobileUsed / 1e6)} of ${Net.LIMIT_TEXT} this month.",
            color = Palette.Dust, size = 14)
        if (stops.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Label("When the music stopped by itself")
            val now = System.currentTimeMillis()
            for ((at, why) in stops.take(6)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Text(why, Modifier.weight(1f).padding(end = 12.dp), color = Palette.Dust, size = 14)
                    Text(DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString(), color = Palette.Faint, size = 12)
                }
            }
        }
        Spacer(Modifier.height(36.dp))
        Text("doppler ${BuildConfig.VERSION_NAME}", color = Palette.Faint, size = 12, modifier = Modifier.padding(bottom = 16.dp))
    }
}

// ---------------------------------------------------------------- "out loud?"

@Composable
fun SpeakerQuestion(pending: PendingPlay, state: UiState, answer: (Boolean) -> Unit) {
    Ask("Play out loud?", "Nothing is plugged in or connected, so this would come from the phone's own speaker.",
        "Play out loud", { answer(false) }, cancel = "Don't play") { answer(true) }
}

// ---------------------------------------------------------------- small parts

@Composable
fun Text(text: String, modifier: Modifier = Modifier, color: Color = Palette.Starlight, size: Int = 16,
         weight: FontWeight = FontWeight.Normal, family: androidx.compose.ui.text.font.FontFamily = Type.Sans,
         align: TextAlign = TextAlign.Start, maxLines: Int = Int.MAX_VALUE) {
    androidx.compose.material3.Text(text, modifier, style = TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp,
        lineHeight = (size * 1.3f).sp, color = color, textAlign = align), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Header(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) { BackButton(back) }
    Title(title)
}

@Composable
private fun BackButton(back: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = back).semantics { contentDescription = "Back" }, contentAlignment = Alignment.CenterStart) {
        Canvas(Modifier.size(18.dp)) {
            val s = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
            drawPath(Path().apply { moveTo(size.width * 0.6f, 0f); lineTo(size.width * 0.1f, size.height / 2); lineTo(size.width * 0.6f, size.height) }, Palette.Starlight, style = s)
        }
    }
}

@Composable
private fun Title(title: String) {
    if (title == "1" || title == "2") DotNumeral(title, 46.dp, Palette.Starlight, lit = false, animate = false,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp).semantics { contentDescription = title })
    else Text(title, color = Palette.Starlight, size = 40, family = Type.Display, maxLines = 2, modifier = Modifier.padding(bottom = 4.dp))
}

/** Back on the left, quiet icons on the right. The magnifier opens into the search field in place. */
@Composable
private fun TopBar(back: () -> Unit, searching: Boolean, find: String, change: (String) -> Unit, hint: String,
                   openSearch: (() -> Unit)?, closeSearch: () -> Unit, sortOn: Boolean = false, openSort: (() -> Unit)? = null,
                   openMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(back)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            AnimatedContent(searching, transitionSpec = {
                (fadeIn(tween(160)) + expandHorizontally(tween(200), expandFrom = Alignment.End)) togetherWith fadeOut(tween(90)) using SizeTransform(clip = false)
            }, contentAlignment = Alignment.CenterEnd, label = "search") { open ->
                if (open) SearchField(find, change, hint, closeSearch)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    openSearch?.let { IconButton("Find", it) { c -> drawFind(c) } }
                    openSort?.let { IconButton(if (sortOn) "Sort, changed" else "Sort", it, dot = sortOn) { c -> drawSort(c) } }
                    openMore?.let { IconButton("More", it) { c -> drawMore(c) } }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, change: (String) -> Unit, hint: String, close: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.padding(start = 8.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(Palette.Panel)
        .border(1.dp, Palette.Hairline, RoundedCornerShape(22.dp)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(15.dp)) { drawFind(Palette.Dust) }
        Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = Palette.Faint, size = 15, maxLines = 1)
            BasicTextField(value, change, singleLine = true, cursorBrush = SolidColor(Palette.Starlight),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = Palette.Starlight),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        IconButton("Close search", close) { c -> drawClose(c) }
    }
}

@Composable
private fun IconButton(label: String, onClick: () -> Unit, dot: Boolean = false, tint: Color = Palette.Starlight, draw: DrawScope.(Color) -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) { draw(tint) }
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(5.dp).clip(CircleShape).background(Palette.Starlight))
    }
}

private fun DrawScope.drawFind(c: Color) {
    val w = 1.6.dp.toPx()
    val r = size.width * 0.32f
    val o = Offset(size.width * 0.42f, size.height * 0.42f)
    drawCircle(c, r, o, style = Stroke(w))
    drawLine(c, Offset(o.x + r * 0.72f, o.y + r * 0.72f), Offset(size.width * 0.95f, size.height * 0.95f), w, StrokeCap.Round)
}

private fun DrawScope.drawSort(c: Color) {
    val w = 1.6.dp.toPx()
    for ((i, f) in listOf(1f, 0.68f, 0.36f).withIndex()) {
        val y = size.height * (0.2f + i * 0.3f)
        drawLine(c, Offset(size.width * 0.05f, y), Offset(size.width * (0.05f + 0.9f * f), y), w, StrokeCap.Round)
    }
}

private fun DrawScope.drawMore(c: Color) {
    for (i in 0..2) drawCircle(c, 1.7.dp.toPx(), Offset(size.width * (0.15f + i * 0.35f), size.height / 2))
}

private fun DrawScope.drawChevron(c: Color) {
    val w = 1.6.dp.toPx()
    drawPath(Path().apply { moveTo(w, w); lineTo(size.width - w, size.height / 2); lineTo(w, size.height - w) }, c,
        style = Stroke(width = w, cap = StrokeCap.Round))
}

/** A cog: eight square teeth round a ring, with the hole in the middle. */
private fun DrawScope.drawGear(c: Color) {
    val w = 1.5.dp.toPx()
    val o = Offset(size.width / 2, size.height / 2)
    val outer = size.minDimension * 0.5f - w / 2
    val inner = outer * 0.74f
    val path = Path()
    val steps = 32
    for (k in 0..steps) {
        val a = (k - 0.5) * 2 * Math.PI / steps
        val r = if ((k / 2) % 2 == 0) outer else inner
        val p = Offset(o.x + (r * kotlin.math.cos(a)).toFloat(), o.y + (r * kotlin.math.sin(a)).toFloat())
        if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, c, style = Stroke(width = w, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawCircle(c, outer * 0.3f, o, style = Stroke(w))
}

private fun DrawScope.drawClose(c: Color) {
    val w = 1.6.dp.toPx()
    val a = size.width * 0.22f
    val b = size.width * 0.78f
    drawLine(c, Offset(a, a), Offset(b, b), w, StrokeCap.Round)
    drawLine(c, Offset(b, a), Offset(a, b), w, StrokeCap.Round)
}

/** The one big thing on a playlist page, in the color of its key. */
@Composable
private fun PlayButton(playing: Boolean, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(60.dp).clip(CircleShape).background(color).clickable(onClick = onClick)
        .semantics { contentDescription = if (playing) "Pause" else "Play" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.padding(start = if (playing) 0.dp else 3.dp).size(20.dp)) { if (playing) drawPause(Palette.Void) else drawPlay(Palette.Void) }
    }
}

/** Which key the playlist is on: the lit numeral is its key, tap the other to move it there. */
@Composable
private fun KeyToggle(slot: String, on: Boolean, put: () -> Unit) {
    val color = Palette.slot(slot)
    Box(Modifier.padding(start = 8.dp).size(42.dp).clip(CircleShape)
        .then(if (on) Modifier.background(color.copy(alpha = 0.16f)).border(1.dp, color, CircleShape) else Modifier.border(1.dp, Palette.Hairline, CircleShape))
        .clickable(enabled = !on, onClick = put)
        .semantics { contentDescription = if (on) "On key $slot" else "Put on key $slot" }, contentAlignment = Alignment.Center) {
        DotNumeral(slot, 16.dp, if (on) color else Palette.Dust, lit = false, animate = false)
    }
}

@Composable
private fun SheetItem(label: String, color: Color = Palette.Starlight, onClick: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 13.dp), color = color, size = 16)
}

@Composable
private fun Section(title: String) {
    Text(title, color = Palette.Dust, size = 14, weight = FontWeight.Medium, modifier = Modifier.padding(top = 26.dp, bottom = 4.dp))
}

@Composable
private fun Label(title: String) {
    Text(title, color = Palette.Dust, size = 13, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Pill(label: String, strong: Boolean = false, color: Color = Palette.Starlight, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)
        .then(if (strong) Modifier.background(color.copy(alpha = 0.14f)) else Modifier)
        .border(1.dp, if (strong) color.copy(alpha = 0.7f) else Palette.Hairline, RoundedCornerShape(50))
        .padding(horizontal = 15.dp, vertical = 9.dp), color = color, size = 14, weight = FontWeight.Medium)
}

@Composable
private fun Field(value: String, change: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Panel).border(1.dp, Palette.Hairline, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text(hint, color = Palette.Faint, size = 16, maxLines = 1)
        BasicTextField(value, change, singleLine = true, cursorBrush = SolidColor(Palette.Starlight),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, color = Palette.Starlight), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Sheet(dismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Palette.Panel)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(26.dp)).padding(22.dp)) { content() }
    }
}

@Composable
private fun Ask(title: String, body: String, ok: String, dismiss: () -> Unit, cancel: String = "Cancel", confirm: () -> Unit) {
    Sheet(dismiss) {
        Text(title, color = Palette.Starlight, size = 24, family = Type.Display)
        Text(body, color = Palette.Dust, size = 15, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            Pill(cancel, onClick = dismiss)
            Pill(ok, true, Palette.Red, onClick = confirm)
        }
    }
}

@Composable
private fun NameDialog(title: String, start: String, ok: String, dismiss: () -> Unit, done: (String) -> Unit) {
    var name by remember { mutableStateOf(start) }
    Sheet(dismiss) {
        Text(title, color = Palette.Starlight, size = 24, family = Type.Display, modifier = Modifier.padding(bottom = 14.dp))
        Field(name, { name = it }, "A name")
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            Pill("Cancel", onClick = dismiss)
            Pill(ok, true) { if (name.isNotBlank()) done(name.trim()) }
        }
    }
}

@Suppress("unused")
private fun near(a: Float, b: Float) = abs(a - b) < 0.001f
