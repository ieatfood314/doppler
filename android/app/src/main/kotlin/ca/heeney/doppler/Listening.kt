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

    }
}

private fun <T> JSONArray?.rows(make: (JSONArray) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optJSONArray(i)?.let { runCatching { make(it) }.getOrNull() } }

/** A year as the hub tells it, for its story: time heard month by month, how many songs and artists,
 * and the artists and songs heard most. */
data class YearHeard(
    val year: Int, val seconds: Long, val plays: Int, val songs: Int, val artistCount: Int,
    val months: List<Long>, val artists: List<Pair<String, Long>>, val top: List<Triple<String, Long, Int>>,
) {
    /** The month heard most, 0 for January. */
    val best: Int get() = months.indices.maxByOrNull { months[it] } ?: 0

    companion object {
        fun from(o: JSONObject): YearHeard {
            val m = o.optJSONArray("months")
            return YearHeard(
                o.optInt("year"), o.optLong("seconds"), o.optInt("plays"), o.optInt("songs"), o.optInt("artistCount"),
                (0 until 12).map { m?.optLong(it) ?: 0L },
                o.optJSONArray("artists").rows { it.getString(0) to it.getLong(1) },
                o.optJSONArray("top").rows { Triple(it.getString(0), it.getLong(1), it.optInt(2)) },
            )
        }

        /** The year whose story is out in [today]'s month: this one in December, last one in January. */
        fun season(today: YearMonth): Int? = when (today.monthValue) { 12 -> today.year; 1 -> today.year - 1; else -> null }

        /** The year whose story Listening offers while it shows [month]: the month's own year once that
         * year is over (or in its December), and last year in January. Only a year with something heard. */
        fun storyFor(month: YearMonth, today: YearMonth, heard: List<MonthHeard>): Int? {
            val year = if (month == today && today.monthValue == 1) month.year - 1 else month.year
            if (year > today.year || (year == today.year && today.monthValue != 12)) return null
            return year.takeIf { heard.any { it.month.startsWith("$year-") && it.seconds > 0 } }
        }
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

    /** A moved song's path id changes on the hub. Carry both saved and unsent totals to its new id. */
    fun rename(moves: Map<String, String>): Boolean {
        if (moves.isEmpty()) return false
        var changed = false
        fun fix(root: JSONObject) {
            for (month in root.keys()) {
                val tracks = root.optJSONObject(month) ?: continue
                for ((old, new) in moves) {
                    val value = tracks.optJSONArray(old) ?: continue
                    tracks.remove(old)
                    val other = tracks.optJSONArray(new)
                    tracks.put(new, if (other == null) value else JSONArray()
                        .put(maxOf(value.optDouble(0), other.optDouble(0))).put(maxOf(value.optInt(1), other.optInt(1))))
                    changed = true
                }
            }
        }
        fix(mine)
        fix(out)
        heard?.let { (track, since) ->
            moves[track]?.let { heard = it to since; changed = true }
        }
        if (changed) save()
        return changed
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
        save()   // the totals too: after a kill, a total behind what the hub has would count nothing until it caught up
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
