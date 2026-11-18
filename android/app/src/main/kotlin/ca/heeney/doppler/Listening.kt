package ca.heeney.doppler

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/** One month as the hub tells it: time heard and plays on every device together. */
data class MonthHeard(
    val month: String, val seconds: Long, val plays: Int, val songs: Int,
    val artists: List<Pair<String, Long>>, val top: List<Triple<String, Long, Int>>,
) {
    companion object {
        fun from(o: JSONObject) = MonthHeard(
            o.optString("month"), o.optLong("seconds"), o.optInt("plays"), o.optInt("songs"),
            o.optJSONArray("artists").rows { it.getString(0) to it.getLong(1) },
            o.optJSONArray("top").rows { Triple(it.getString(0), it.getLong(1), it.optInt(2)) },
        )

        private fun <T> JSONArray?.rows(make: (JSONArray) -> T): List<T> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optJSONArray(i)?.let { runCatching { make(it) }.getOrNull() } }
    }
}

/**
 * This phone's listening time, month by month: {month: {track: [seconds heard, plays]}}, kept as
 * running totals like the play counts, so a resend or a late one is harmless (the hub keeps the
 * larger). Time counts only while sound is coming out: not paused, not buffering.
 */
class Listening(private val store: Store) {
    private val mine = store.loadJson("listened.json") ?: JSONObject()       // this month and last
    private val out = store.loadJson("listened-out.json") ?: JSONObject()    // ...what the hub hasn't had yet
    private var heard: Pair<String, Long>? = null   // the track sounding since then (elapsedRealtime)
    private var savedAt = 0L
    var sentAt = 0L; private set

    /** Credit the time since the last look to the track that was sounding then, and start again
     * with [sounding] (null when nothing can be heard). Call it whenever that might have changed. */
    fun tally(sounding: String?) {
        val now = SystemClock.elapsedRealtime()
        heard?.let { (track, since) ->
            val seconds = (now - since) / 1000.0
            if (seconds > 0 && seconds < 3600) credit(track, seconds)
        }
        heard = sounding?.let { it to now }
        if (sounding == null || now - savedAt > 60_000) save()
    }

    fun credit(track: String, seconds: Double = 0.0, plays: Int = 0) {
        val month = YearMonth.now().toString()
        if (!mine.has(month)) prune()
        val tracks = mine.optJSONObject(month) ?: JSONObject().also { mine.put(month, it) }
        val old = tracks.optJSONArray(track)
        val entry = JSONArray().put(Math.round(((old?.optDouble(0) ?: 0.0) + seconds) * 10) / 10.0).put((old?.optInt(1) ?: 0) + plays)
        tracks.put(track, entry)
        (out.optJSONObject(month) ?: JSONObject().also { out.put(month, it) }).put(track, entry)
        if (plays > 0) save()
    }

    /** Whether there's time to send: with a pause, or every few minutes while playing. */
    fun due(playing: Boolean) = out.length() > 0 && (!playing || SystemClock.elapsedRealtime() - sentAt >= SEND_EVERY_MS)
    fun waiting() = out.length() > 0

    /** A copy of what to send; hand it back to [sent] once the hub has it. */
    fun sending(): JSONObject = JSONObject(out.toString())

    fun sent(what: JSONObject) {
        sentAt = SystemClock.elapsedRealtime()
        for (month in what.keys()) {
            val sentTracks = what.getJSONObject(month)
            val waiting = out.optJSONObject(month) ?: continue
            for (track in sentTracks.keys()) {
                if (waiting.optJSONArray(track)?.toString() == sentTracks.getJSONArray(track).toString()) waiting.remove(track)
            }
            if (waiting.length() == 0) out.remove(month)
        }
        store.saveJson("listened-out.json", out)
    }

    private fun save() {
        savedAt = SystemClock.elapsedRealtime()
        store.saveJson("listened.json", mine)
        store.saveJson("listened-out.json", out)
    }

    /** Keep this month and last (and anything still waiting to go); the hub has the rest. */
    private fun prune() {
        val now = YearMonth.now()
        val keep = setOf(now.toString(), now.minusMonths(1).toString()) + out.keys().asSequence()
        for (month in mine.keys().asSequence().toList()) if (month !in keep) mine.remove(month)
    }

    companion object {
        const val SEND_EVERY_MS = 5 * 60_000L
    }
}
