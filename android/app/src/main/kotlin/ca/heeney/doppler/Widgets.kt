package ca.heeney.doppler

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** The 2x1, 1x2 and 2x2 widgets. Stopped, they show the 1 and the 2, and a tap plays that one; the 2x2 also
 * shows today's screen time from lightcurve above them. While the phone plays they show the cover, title and
 * artist with the playing number small and red in the corner: a tap in the middle pauses, the left and right
 * edges go back and skip, and the number opens its playlist. Pausing, from anywhere, brings back the 1 and 2.
 * (A widget only gets taps: the launcher keeps swipes and press-and-hold.) Colours come from resources, so
 * the launcher picks day or night. */
object Widgets {
    /** What a playing widget shows. */
    private data class Live(val playlist: String, val slot: String?, val track: String?, val title: String, val artist: String)

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var shown: List<Any?>? = null
    private var coverOf: String? = null   // the track the cover below belongs to (or is loading for)
    private var cover: Bitmap? = null     // square and unrounded; each widget rounds its own copy (see coverFor)
    private val roundedCovers = HashMap<Int, Bitmap>()   // by corner radius in bitmap pixels
    @Volatile private var wanted: String? = null   // lets queued loads for skipped songs bow out
    private var missed: Pair<String, Long>? = null  // a cover that didn't load, and when to try again

    private fun live(state: UiState): Live? {
        val playlist = state.playlist
        if (!state.playing || playlist == null) return null
        val t = state.track
        return Live(playlist, state.slots.entries.firstOrNull { it.value == playlist }?.key, t?.id,
            t?.title?.ifBlank { null } ?: state.playlistName, t?.artist ?: "")
    }

    private fun name(state: UiState, slot: String) = state.playlists.firstOrNull { it.id == state.slots[slot] }?.name

    /** Called with every published state, on the main thread; pushes only when what shows has changed. */
    fun update(context: Context, state: UiState) {
        val manager = AppWidgetManager.getInstance(context)
        val placed = PROVIDERS.map { (provider, layout) -> manager.getAppWidgetIds(ComponentName(context, provider)) to layout }
            .filter { it.first.isNotEmpty() }
        if (placed.isEmpty()) return
        val live = live(state)
        val retry = missed.let { it == null || it.first != live?.track || SystemClock.elapsedRealtime() >= it.second }
        if (live?.track != null && live.track != coverOf && retry) loadCover(context, live.track)
        val square = placed.any { it.second == R.layout.widget_square }
        val time = if (square) ScreenTime.today(context) else null
        val key = listOf(live, name(state, "1"), name(state, "2"), state.playlistName, cover.takeIf { live != null }, time)
        if (key == shown) return
        // Back to the 1 and 2 (or drawn for the first time): a good moment for a fresh screen time.
        if (square && live == null && (shown == null || shown?.firstOrNull() != null)) ScreenTime.ask(context)
        shown = key
        for ((ids, layout) in placed) manager.updateAppWidget(ids, views(context, layout, live, state))
    }

    /** Redraws whatever is placed, even if nothing seems to have changed (say the process died mid-song). */
    fun refresh(context: Context) {
        shown = null
        // A process with no engine plays nothing, so the 1 and 2 are right without starting one.
        update(context, Engine.peek()?.state?.value ?: UiState())
    }

    private fun views(context: Context, layout: Int, live: Live?, state: UiState): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        views.setViewVisibility(R.id.idle, if (live == null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.live, if (live == null) View.GONE else View.VISIBLE)
        if (live == null) {
            if (layout == R.layout.widget_square) showTime(context, views)
            for ((slot, button) in listOf("1" to R.id.slot1, "2" to R.id.slot2)) {
                val intent = Intent(context, ConfirmActivity::class.java).setAction(ConfirmActivity.SLOT).putExtra("slot", slot)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                views.setOnClickPendingIntent(button, PendingIntent.getActivity(context, slot.toInt(), intent, FLAGS))
                views.setContentDescription(button, "Play ${name(state, slot) ?: slot}")
            }
            return views
        }
        views.setTextViewText(R.id.title, live.title)
        views.setTextViewText(R.id.artist, live.artist)
        views.setViewVisibility(R.id.artist, if (live.artist.isBlank()) View.GONE else View.VISIBLE)
        val hasArt = cover != null && coverOf == live.track
        if (layout == R.layout.widget_square) {
            views.setImageViewResource(R.id.lit, when (live.slot) { "1" -> R.drawable.dots_1; "2" -> R.drawable.dots_2; else -> R.drawable.dots_star })
            // Corners concentric with the card's: 24dp less the 14dp padding, whatever size the cover is.
            val side = if (Build.VERSION.SDK_INT >= 31) coverSide(context, live.title) else 72
            if (Build.VERSION.SDK_INT >= 31) {
                views.setViewLayoutWidth(R.id.cover, side.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.cover, side.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            }
            if (hasArt) views.setImageViewBitmap(R.id.cover, coverFor(10f / side))
            else views.setImageViewResource(R.id.cover, R.drawable.widget_cover_square)
        } else {
            views.setImageViewResource(R.id.lit, when (live.slot) { "1" -> R.drawable.glyph_1; "2" -> R.drawable.glyph_2; else -> R.drawable.glyph_dot })
            if (hasArt) views.setImageViewBitmap(R.id.cover, coverFor(0.23f))   // about 14dp on a 60dp cover
            else views.setImageViewResource(R.id.cover, R.drawable.widget_cover)
        }
        val open = Intent(context, MainActivity::class.java).setAction(MainActivity.OPEN).putExtra("playlist", live.playlist)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        views.setOnClickPendingIntent(R.id.open, PendingIntent.getActivity(context, 10, open, FLAGS))
        views.setContentDescription(R.id.open, "Open ${state.playlistName.ifBlank { "playlist" }}")
        for ((zone, action) in listOf(R.id.prev to WidgetButtons.PREV, R.id.pause to WidgetButtons.PAUSE, R.id.next to WidgetButtons.NEXT)) {
            views.setOnClickPendingIntent(zone, PendingIntent.getBroadcast(context, zone, Intent(context, WidgetButtons::class.java).setAction(action), FLAGS))
        }
        return views
    }

    // ---------- the 2x2's screen time and title

    private val DIGITS = intArrayOf(R.drawable.dots_0, R.drawable.dots_1, R.drawable.dots_2, R.drawable.dots_3, R.drawable.dots_4,
        R.drawable.dots_5, R.drawable.dots_6, R.drawable.dots_7, R.drawable.dots_8, R.drawable.dots_9)

    /** "2H 49M", "49M", "3H", in stars; nothing at all while lightcurve hasn't said anything about today. */
    private fun showTime(context: Context, views: RemoteViews) {
        ScreenTime.open(context)?.let {
            views.setOnClickPendingIntent(R.id.time, PendingIntent.getActivity(context, 20, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS))
        }
        val lit = ScreenTime.today(context)
        views.setViewVisibility(R.id.clock, if (lit == null) View.GONE else View.VISIBLE)
        if (lit == null) {
            views.setContentDescription(R.id.time, "Screen time")
            return
        }
        val minutes = (lit / 60).toInt()
        val h = minutes / 60
        val m = minutes % 60
        // A round hour drops its "0M", where the layout can take the gap after the H away with it.
        val showMinutes = h == 0 || m != 0 || Build.VERSION.SDK_INT < 31
        fun digit(id: Int, value: Int, show: Boolean) {
            views.setViewVisibility(id, if (show) View.VISIBLE else View.GONE)
            if (show) views.setImageViewResource(id, DIGITS[value])
        }
        views.setViewVisibility(R.id.hours, if (h > 0) View.VISIBLE else View.GONE)
        digit(R.id.h1, h / 10 % 10, h >= 10)
        digit(R.id.h2, h % 10, h > 0)
        if (Build.VERSION.SDK_INT >= 31) views.setViewLayoutMargin(R.id.hletter, RemoteViews.MARGIN_END, if (showMinutes) 9.9f else 0f, TypedValue.COMPLEX_UNIT_DIP)
        digit(R.id.m1, m / 10, showMinutes && m >= 10)
        digit(R.id.m2, m % 10, showMinutes)
        views.setViewVisibility(R.id.mletter, if (showMinutes) View.VISIBLE else View.GONE)
        val said = listOfNotNull(if (h > 0) "$h hour${if (h == 1) "" else "s"}" else null,
            if (showMinutes) "$m minute${if (m == 1) "" else "s"}" else null).joinToString(" ")
        views.setContentDescription(R.id.time, "Screen time today, $said")
    }

    /** The cover gives way to a long title: the biggest cover that still leaves the title a comfortable size.
     * The title then sizes itself to its box (autosize), so it is never cut, whatever the launcher's cell. */
    private fun coverSide(context: Context, title: String): Int {
        val dm = context.resources.displayMetrics
        val dp = dm.density
        val inner = 158 - 2 * 14   // the Nothing launcher's 2x2, less padding
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = runCatching { context.resources.getFont(R.font.space_grotesk) }.getOrNull()
        }
        fun fits(cover: Int, sp: Float): Boolean {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
            val layout = StaticLayout.Builder.obtain(title, 0, title.length, paint, (inner * dp).toInt())
                .setLineSpacing(0f, 0.95f).setIncludePad(false).build()
            val box = inner - cover - 8 - 2 - 17
            val unbroken = title.split(" ").all { paint.measureText(it) <= inner * dp }
            return unbroken && layout.height <= box * dp
        }
        return when {
            fits(72, 14f) -> 72
            fits(60, 12f) -> 60
            else -> 48
        }
    }

    // ---------- the cover

    private const val COVER_PX = 200

    /** The phone's own copy's embedded picture first (no network), then the hub's. Off the main thread;
     * the answer is dropped if the song has moved on by then. */
    private fun loadCover(context: Context, track: String) {
        val engine = Engine.get(context)
        coverOf = track
        wanted = track
        cover = null
        roundedCovers.clear()
        val file = engine.localUri(track)
        val url = engine.artUrl(engine.track(track), COVER_PX * 2)
        loader.execute {
            if (wanted != track) return@execute
            val bytes = file?.let { embedded(context, it) } ?: url?.let { fetch(it) }
            val bitmap = bytes?.let { runCatching { square(it) }.getOrNull() }
            main.post {
                if (coverOf != track) return@post
                if (bitmap != null) {
                    cover = bitmap
                    missed = null
                } else {
                    // Maybe the hub was away, or the library hadn't loaded yet: let a later update try again.
                    coverOf = null
                    missed = track to SystemClock.elapsedRealtime() + if (url != null) 30_000 else 3_000
                }
                update(context, engine.state.value)
            }
        }
    }

    private fun embedded(context: Context, uri: Uri): ByteArray? = runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(context, uri); r.embeddedPicture } finally { r.release() }
    }.getOrNull()

    private fun fetch(url: String): ByteArray? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } finally { conn.disconnect() }
    }.getOrNull()

    /** The cover with corners of [fraction] of its side, made once per size. */
    private fun coverFor(fraction: Float): Bitmap {
        val src = cover!!
        val radius = (COVER_PX * fraction).toInt()
        return roundedCovers.getOrPut(radius) {
            val out = Bitmap.createBitmap(COVER_PX, COVER_PX, Bitmap.Config.ARGB_8888)
            Canvas(out).drawRoundRect(RectF(0f, 0f, COVER_PX.toFloat(), COVER_PX.toFloat()), radius.toFloat(), radius.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) })
            out
        }
    }

    /** A square, centre-cropped, small enough to hand to the launcher. */
    private fun square(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_PX) sample *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(COVER_PX, COVER_PX, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply {
                val scale = COVER_PX.toFloat() / side
                setScale(scale, scale)
                postTranslate(-(src.width - side) / 2f * scale, -(src.height - side) / 2f * scale)
            })
        }
        Canvas(out).drawRect(0f, 0f, COVER_PX.toFloat(), COVER_PX.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        src.recycle()
        return out
    }

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private val PROVIDERS = listOf(WideWidget::class.java to R.layout.widget_wide, TallWidget::class.java to R.layout.widget_tall,
        SquareWidget::class.java to R.layout.widget_square)
}

/** The back, pause and skip zones of a playing widget. They only touch music that is already playing,
 * so a widget left showing a song after the process died just goes back to the 1 and 2. */
class WidgetButtons : BroadcastReceiver() {
    companion object {
        const val PREV = "ca.heeney.doppler.WIDGET_PREV"
        const val PAUSE = "ca.heeney.doppler.WIDGET_PAUSE"
        const val NEXT = "ca.heeney.doppler.WIDGET_NEXT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val engine = Engine.get(context)
        if (!engine.state.value.playing) return Widgets.refresh(context)
        when (intent.action) {
            PREV -> engine.previous()
            PAUSE -> engine.pause()
            NEXT -> engine.next()
        }
    }
}

abstract class SlotWidget : AppWidgetProvider() {
    /** A newly placed widget gets the current look, and its cover loads if it wasn't already. */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refresh(context)
}

class WideWidget : SlotWidget()
class TallWidget : SlotWidget()
class SquareWidget : SlotWidget()
