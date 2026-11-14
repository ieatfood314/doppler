package ca.heeney.doppler

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Track(
    val id: String, val title: String, val artist: String, val album: String, val duration: Double, val art: Boolean,
    val albumArtist: String = "", val n: Int = 0, val disc: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("artist", artist).put("album", album)
        .put("duration", duration).put("art", art).put("albumartist", albumArtist).put("n", n).put("disc", disc)

    companion object {
        fun from(o: JSONObject) = Track(o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
            o.optDouble("duration", 0.0), o.optBoolean("art"), o.optString("albumartist"), o.optInt("n"), o.optInt("disc"))

        fun guess(id: String): Track {
            val (artist, title) = Core.splitTitle(id)
            return Track(id, title, artist, "", 0.0, false)
        }
    }
}

/** A playlist's place: which run through it, how far along, and who was playing it. */
data class Session(
    val playlist: String, val order: List<String>, val index: Int, val position: Double, val playing: Boolean,
    val device: String, val updated: Double, val unsent: Boolean = false,
) {
    val orderHash: String by lazy { Core.orderHash(order) }
    val track: String? get() = order.getOrNull(index)

    fun toJson(): JSONObject = JSONObject().put("playlist", playlist).put("order", JSONArray(order)).put("index", index)
        .put("position", position).put("playing", playing).put("device", device).put("updated", updated).put("unsent", unsent)

    companion object {
        fun from(o: JSONObject, fallbackOrder: List<String>? = null): Session {
            val order = o.optJSONArray("order")?.let { a -> List(a.length()) { a.getString(it) } } ?: fallbackOrder ?: emptyList()
            return Session(o.getString("playlist"), order, o.optInt("index"), o.optDouble("position", 0.0), o.optBoolean("playing"),
                o.optString("device"), o.optDouble("updated", 0.0), o.optBoolean("unsent"))
        }
    }
}

/** What the hub says about one session without its order: enough to show "left off at". */
data class SessionInfo(val playlist: String, val index: Int, val position: Double, val playing: Boolean, val device: String,
                       val updated: Double, val track: String?, val orderHash: String)

data class Active(val device: String, val playlist: String, val since: Double)

data class HubState(
    val rev: Long, val now: Double, val active: Active?, val sessions: Map<String, SessionInfo>, val slots: Map<String, String>,
    val playlists: Map<String, CustomPlaylist>, val modes: Map<String, String>, val libraryRev: String, val playsRev: Long = 0,
) {
    companion object {
        fun from(o: JSONObject): HubState {
            val sessions = HashMap<String, SessionInfo>()
            o.optJSONObject("sessions")?.let { s ->
                for (key in s.keys()) {
                    val x = s.getJSONObject(key)
                    sessions[key] = SessionInfo(key, x.optInt("index"), x.optDouble("position", 0.0), x.optBoolean("playing"),
                        x.optString("device"), x.optDouble("updated", 0.0), if (x.isNull("track")) null else x.optString("track"),
                        x.optString("orderHash"))
                }
            }
            val playlists = LinkedHashMap<String, CustomPlaylist>()
            o.optJSONArray("playlists")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    val t = p.optJSONArray("tracks") ?: JSONArray()
                    playlists[p.getString("id")] = CustomPlaylist(p.getString("id"), p.optString("name"), List(t.length()) { t.getString(it) })
                }
            }
            val active = o.optJSONObject("active")?.let { Active(it.getString("device"), it.getString("playlist"), it.getDouble("since")) }
            return HubState(o.optLong("rev"), o.optDouble("now"), active, sessions, o.optJSONObject("slots").strings(),
                playlists, o.optJSONObject("modes").strings(), o.optJSONObject("library")?.optString("rev") ?: "",
                o.optLong("playsRev"))
        }

        private fun JSONObject?.strings(): Map<String, String> {
            if (this == null) return emptyMap()
            val out = HashMap<String, String>()
            for (key in keys()) out[key] = getString(key)
            return out
        }
    }
}

/** Plain HTTP to the hub. Every call blocks, so run them off the main thread.
 *
 * Nothing goes over mobile data unless the call says [progress] (a session report, or the session
 * read before a press of play); those are counted against the month's allowance in [Net]. */
class Hub(val base: String) {
    class Reply(val code: Int, val body: String, val etag: String?)

    fun request(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 6000, etag: String? = null,
                progress: Boolean = false): Reply {
        if (base.isBlank()) throw IOException("no hub set")
        val onWifi = Net.check()
        if (!onWifi) {
            if (!progress) throw NotOnWifi()
            if (!Net.hasBudget()) throw NotOnWifi("this month's mobile data is used up")
        }
        val out = body?.toString()?.toByteArray()
        var received = 0L
        val conn = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        if (onWifi) Net.holdWhileOnWifi(conn)
        var keep = false
        try {
            if (onWifi && !progress && !Net.free) throw NotOnWifi()   // left the Wi-Fi in the last moment
            conn.requestMethod = method
            conn.connectTimeout = minOf(timeoutMs, 8000)
            conn.readTimeout = timeoutMs
            if (etag != null) conn.setRequestProperty("If-None-Match", etag)
            if (out != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(out.size)
                conn.outputStream.use { it.write(out) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else if (code == 304) null else conn.inputStream
            val bytes = java.io.ByteArrayOutputStream()
            stream?.use { s ->
                val chunk = ByteArray(8192)
                while (true) {
                    // a download that started on Wi-Fi stops the moment the phone leaves it
                    if (!progress && !Net.free) throw NotOnWifi()
                    val n = s.read(chunk)
                    if (n < 0) break
                    bytes.write(chunk, 0, n)
                }
            }
            received = bytes.size().toLong()
            // Over mobile data the connection is kept for the next report, which saves a TLS handshake each time.
            keep = !onWifi
            return Reply(code, bytes.toString("UTF-8"), conn.getHeaderField("ETag"))
        } finally {
            Net.release(conn)
            if (!keep) conn.disconnect()
            if (!onWifi) Net.charged((out?.size ?: 0) + received + path.length + 1500L)
        }
    }

    fun json(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 6000, progress: Boolean = false): JSONObject {
        val reply = request(method, path, body, timeoutMs, progress = progress)
        if (reply.code >= 500) throw IOException("hub answered ${reply.code}")
        return try { JSONObject(reply.body) } catch (e: Exception) { throw IOException("hub sent something odd") }
    }

    fun fileUrl(track: String) = base.trimEnd('/') + "/file/" + enc(track)
    fun artUrl(track: String, size: Int = 640) = base.trimEnd('/') + "/art/" + enc(track) + "?s=$size"

    companion object {
        fun enc(path: String) = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        fun q(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
