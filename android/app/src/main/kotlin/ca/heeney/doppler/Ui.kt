package ca.heeney.doppler

import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
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
    data class Playlist(val id: String) : Screen
    data object Settings : Screen
}

@Composable
fun App(engine: Engine, askFiles: () -> Unit) {
    val state by engine.state.collectAsState()
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val screen = stack.last()
    BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
    Box(Modifier.fillMaxSize().background(Palette.Void)) {
        Starfield(Modifier.fillMaxSize())
        AnimatedContent(screen, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "screen") { s ->
            when (s) {
                Screen.Home -> Home(engine, state, open = { stack.add(it) })
                Screen.Library -> Library(engine, state, open = { stack.add(it) }, back = { stack.removeAt(stack.lastIndex) })
                is Screen.Playlist -> PlaylistPage(engine, state, s.id, back = { stack.removeAt(stack.lastIndex) })
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
        TopLine(state)
        state.elsewhere?.takeIf { it.playing }?.let { Elsewhere(engine, state, it) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val h = maxHeight
            Column(Modifier.fillMaxSize()) {
                SlotBlock(engine, state, "1", Modifier.weight(1f), open)
                SlotBlock(engine, state, "2", Modifier.weight(1f), open)
            }
            if (h < 10.dp) Spacer(Modifier)
        }
        NowPlaying(engine, state)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("All music", Modifier.clip(RoundedCornerShape(12.dp)).clickable { open(Screen.Library) }.padding(vertical = 10.dp, horizontal = 2.dp),
                color = Palette.Dust, size = 15, weight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(state.output?.let { "To $it" } ?: if (state.onSpeaker) "Phone speaker" else "Connected",
                color = if (state.onSpeaker) Palette.Faint else Palette.Dust, size = 13)
        }
    }
}

@Composable
private fun TopLine(state: UiState) {
    val line = when {
        state.message.isNotEmpty() -> state.message
        state.hubUrl.isBlank() -> "Not synced: add your hub in All music, Settings"
        !state.hubOnline -> "Offline. Picks up the sync when the hub is back"
        else -> ""
    }
    Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.CenterStart) {
        if (line.isNotEmpty()) Text(line, color = Palette.Dust, size = 13, maxLines = 1)
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
            if (right) SlotWords(state, slot, view, lit, color, TextAlign.End, Modifier.weight(1f).padding(end = 6.dp))
            DotNumeral(slot, (numeral * 0.78f).dp, if (lit) color else Palette.Starlight.copy(alpha = if (state.playing) 0.42f else 0.88f),
                lit, animate = !reducedMotion(LocalContext.current), modifier = Modifier.padding(horizontal = 10.dp))
            if (!right) SlotWords(state, slot, view, lit, color, TextAlign.Start, Modifier.weight(1f).padding(start = 6.dp))
        }
    }
}

@Composable
private fun SlotWords(state: UiState, slot: String, view: PlaylistView?, lit: Boolean, color: Color, align: TextAlign, modifier: Modifier) {
    Column(modifier, horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start) {
        Text(when { view == null -> "Not set"; view.name == slot -> "${view.count} tracks"; else -> view.name }, color = Palette.Starlight, size = 26, family = Type.Display, align = align, maxLines = 1)
        val note = when {
            view == null -> "Long-press to choose"
            lit -> "Playing"
            view.lastTrack == null -> "Shuffled, from the top"
            view.lastPlaying && view.lastDevice != state.device -> "Playing on ${view.lastDevice}"
            view.lastDevice != null && view.lastDevice != state.device -> "Left off on ${view.lastDevice}"
            else -> "Picks up where you left off"
        }
        Text(note, color = if (lit) color else Palette.Dust, size = 13, align = align, maxLines = 1)
        view?.lastTrack?.takeIf { !lit }?.let { Text(it.title, color = Palette.Faint, size = 13, align = align, maxLines = 1) }
    }
}

@Composable
private fun NowPlaying(engine: Engine, state: UiState) {
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
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(track.title, color = Palette.Starlight, size = 24, family = Type.Display, maxLines = 1)
        Text(listOf(track.artist, state.playlistName).filter { it.isNotEmpty() }.joinToString(", from "), color = Palette.Dust, size = 14, maxLines = 1)
        Spectrum(track.id, position, duration, color, Modifier.fillMaxWidth().height(40.dp).padding(top = 10.dp)) { engine.seek(it) }
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
}

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** The progress line is a stellar spectrum, with dark absorption lines that are this track's own
 * fingerprint. Drag or tap anywhere on it to move. */
@Composable
fun Spectrum(trackId: String, position: Long, duration: Long, accent: Color, modifier: Modifier, seek: (Long) -> Unit) {
    val lines = remember(trackId) {
        val r = Random(trackId.hashCode())
        List(22) { r.nextFloat() to (0.25f + r.nextFloat() * 0.75f) }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val spectrum = remember { listOf(Color(0xFF6B2BFF), Palette.Blue, Color(0xFF39D0E0), Color(0xFF7BE07A), Color(0xFFFFE15A), Color(0xFFFF9A3C), Palette.Red) }
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
        val band = 6.dp.toPx()
        drawLine(Palette.Hairline, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        val played = size.width * fraction
        if (played > 0) {
            drawRect(Brush.horizontalGradient(spectrum, 0f, size.width), Offset(0f, mid - band / 2), androidx.compose.ui.geometry.Size(played, band))
            for ((x, strength) in lines) {
                val lx = x * size.width
                if (lx < played) drawLine(Color.Black.copy(alpha = strength), Offset(lx, mid - band / 2), Offset(lx, mid + band / 2), 1.6.dp.toPx())
            }
        }
        drawCircle(accent.copy(alpha = 0.25f), 9.dp.toPx(), Offset(played, mid))
        drawCircle(Palette.Starlight, 3.5.dp.toPx(), Offset(played, mid))
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
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 22.dp)) {
        item { Header("All music", back) }
        item {
            val sub = if (state.libraryCount == 0) "Nothing yet: the library comes from the hub"
            else "${state.libraryCount} tracks, ${state.localCount} of them on this phone"
            Text(sub, color = Palette.Dust, size = 14, modifier = Modifier.padding(bottom = 16.dp))
            Field(query, { query = it }, "Find a song, artist or album")
        }
        if (q.length >= 2) {
            if (matches.isEmpty()) item { Text("No song matches that", color = Palette.Dust, size = 15, modifier = Modifier.padding(vertical = 14.dp)) }
            items(matches, key = { "m" + it.id }) { t ->
                TrackRow(engine, t, state.track?.id == t.id, selected = false, onClick = {
                    engine.requestPlay("f:" + t.id.substringBeforeLast("/", ""), t.id)
                }, onLong = {})
            }
        } else {
            item { Section("Folders") }
            items(state.playlists.filter { !it.custom }, key = { it.id }) { p -> PlaylistRow(p, state) { open(Screen.Playlist(p.id)) } }
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
}

@Composable
private fun PlaylistRow(p: PlaylistView, state: UiState, onClick: () -> Unit) {
    val depth = if (p.custom || p.path.isEmpty()) 0 else p.path.count { it == '/' }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
        .padding(start = (depth * 18).dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.name, color = Palette.Starlight, size = if (depth == 0) 18 else 16, family = if (depth == 0) Type.Display else Type.Sans, maxLines = 1)
            val playingHere = state.playing && state.playlist == p.id
            Text(if (playingHere) "Playing" else "${p.count} tracks" + if (p.mode == "order") ", in order" else "",
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
private fun PlaylistPage(engine: Engine, state: UiState, id: String, back: () -> Unit) {
    val view = state.playlists.firstOrNull { it.id == id }
    val tracks = remember(id, state.libraryCount, state.customs) { engine.tracksOf(id).map { engine.track(it)!! } }
    var find by rememberSaveable(id) { mutableStateOf("") }
    var sort by remember { mutableStateOf(engine.store.sort) }
    var choosingSort by remember { mutableStateOf(false) }
    val q = find.trim().lowercase()
    val shown = remember(tracks, q, sort, state.playsRev) { sortTracks(engine, tracks.filter { q.isEmpty() || it.matches(q) }, sort) }
    val selected = remember(id) { mutableStateListOf<String>() }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
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
            sortOn = sort != "default", openSort = if (tracks.size > 1) ({ choosingSort = true }) else null,
            openMore = { more = true })
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Title(view?.name ?: "Playlist")
                val about = buildString {
                    append(if (tracks.size == 1) "1 track" else "${tracks.size} tracks")
                    if (view?.mode == "order") append(", in order")
                    if (custom) append(", made by you")
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
                Text(if (view?.custom == true) "Empty. Find songs in a folder, long-press them and add them here." else "No music in this folder",
                    color = Palette.Dust, size = 15, modifier = Modifier.padding(vertical = 20.dp))
            }
            items(shown, key = { it.id }) { t ->
                val picked = t.id in selected
                TrackRow(engine, t, state.track?.id == t.id && state.playlist == id, picked, note = sortNote(engine, t, sort),
                    onClick = { if (pickMode) { if (picked) selected.remove(t.id) else selected.add(t.id) } else engine.requestPlay(id, t.id) },
                    onLong = { if (picked) selected.remove(t.id) else selected.add(t.id) })
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
                    Pill("Add to playlist", true) { adding = true }
                }
            }
        }
    }
    if (choosingSort) Sheet({ choosingSort = false }) {
        Text("Sort by", color = Palette.Starlight, size = 22, family = Type.Display, modifier = Modifier.padding(bottom = 8.dp))
        for (key in SORTS) {
            val on = key == sort
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                sort = key
                engine.store.sort = key
                choosingSort = false
            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sortName(key, view?.custom == true), Modifier.weight(1f), color = if (on) Palette.Starlight else Palette.Dust, size = 16,
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

private val SORTS = listOf("default", "title", "artist", "album", "most", "least", "recent")

private fun sortName(key: String, custom: Boolean) = when (key) {
    "title" -> "Title"; "artist" -> "Artist"; "album" -> "Album"
    "most" -> "Most played"; "least" -> "Least played"; "recent" -> "Recently played"
    else -> if (custom) "Order added" else "File order"
}

private fun Track.matches(q: String) = title.lowercase().contains(q) || artist.lowercase().contains(q) || album.lowercase().contains(q)

/** Blank names go last; ties keep the playlist's own order (sortedWith is stable). */
private fun sortTracks(engine: Engine, tracks: List<Track>, sort: String): List<Track> {
    fun text(f: (Track) -> String) = compareBy<Track> { f(it).isBlank() }.thenBy(Core.natural, f)
    return when (sort) {
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
private fun TrackRow(engine: Engine, t: Track, current: Boolean, selected: Boolean, note: String? = null, onClick: () -> Unit, onLong: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Palette.Panel else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(vertical = 10.dp, horizontal = 4.dp),
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
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 22.dp)) {
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
            Text("${state.localCount} of ${state.libraryCount} tracks play from the phone itself; the rest stream from the hub.",
                color = Palette.Dust, size = 14)
        } else {
            Text("Everything streams from the hub. Let doppler see your music to play the copies already on the phone.",
                color = Palette.Dust, size = 14, modifier = Modifier.padding(bottom = 10.dp))
            Pill("Use music on this phone", true, onClick = askFiles)
        }
        Spacer(Modifier.weight(1f))
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
                   openSearch: (() -> Unit)?, closeSearch: () -> Unit, sortOn: Boolean, openSort: (() -> Unit)?, openMore: () -> Unit) {
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
                    IconButton("More", openMore) { c -> drawMore(c) }
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
private fun IconButton(label: String, onClick: () -> Unit, dot: Boolean = false, draw: DrawScope.(Color) -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) { draw(Palette.Starlight) }
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
        .padding(horizontal = 15.dp, vertical = 9.dp), color = if (strong) color else Palette.Starlight, size = 14, weight = FontWeight.Medium)
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
