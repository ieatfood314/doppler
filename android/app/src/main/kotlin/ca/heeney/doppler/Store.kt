package ca.heeney.doppler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the phone remembers between runs, so it can play with no network at all. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("doppler", Context.MODE_PRIVATE)
    private val dir = context.filesDir

    var hubUrl: String
        get() = prefs.getString("hub", null) ?: BuildConfig.DEFAULT_HUB
        set(value) = prefs.edit().putString("hub", value.trim().trimEnd('/')).apply()

    var device: String
        get() = prefs.getString("device", null) ?: BuildConfig.DEFAULT_DEVICE.ifEmpty { "phone" }
        set(value) = prefs.edit().putString("device", value.trim().ifEmpty { "phone" }).apply()

    /** This install's own id for play counts. Unlike the phone's name, it never repeats. */
    val source: String
        get() = prefs.getString("source", null) ?: ("$device-" + java.util.UUID.randomUUID().toString().take(12)).also {
            prefs.edit().putString("source", it).apply()
        }

    var sort: String
        get() = prefs.getString("sort", null) ?: "default"
        set(value) = prefs.edit().putString("sort", value).apply()

    var current: String?
        get() = prefs.getString("current", null)
        set(value) = prefs.edit().putString("current", value).apply()

    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
    }

    fun loadLibrary(): Pair<String, Map<String, Track>> {
        val o = read("library.json")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return "" to emptyMap()
        val tracks = o.getJSONArray("tracks")
        val map = LinkedHashMap<String, Track>()
        for (i in 0 until tracks.length()) Track.from(tracks.getJSONObject(i)).let { map[it.id] = it }
        return o.optString("etag") to map
    }

    fun saveLibrary(etag: String, body: String) {
        val o = JSONObject(body).put("etag", etag)
        write("library.json", o.toString())
    }

    fun loadHubState(): HubState? = read("hub.json")?.let { runCatching { HubState.from(JSONObject(it)) }.getOrNull() }
    fun saveHubState(body: String) = write("hub.json", body)

    fun loadSessions(): MutableMap<String, Session> {
        val o = read("sessions.json")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return HashMap()
        val out = HashMap<String, Session>()
        for (key in o.keys()) runCatching { out[key] = Session.from(o.getJSONObject(key)) }
        return out
    }

    fun saveSessions(sessions: Map<String, Session>) {
        val o = JSONObject()
        for ((k, v) in sessions) o.put(k, v.toJson())
        write("sessions.json", o.toString())
    }

    /** Play counts by track: "plays.json" (this phone's own), "plays-out.json" (not yet sent),
     * "plays-hub.json" (everyone's, as the hub last said). */
    fun loadPlays(name: String): MutableMap<String, Play> {
        val o = read(name)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return HashMap()
        return playsFrom(o)
    }

    fun savePlays(name: String, plays: Map<String, Play>) = write(name, playsJson(plays).toString())

    /** Reports the hub hasn't had yet: playlist -> (claim, wall-clock ms when it happened). */
    fun loadOutbox(): MutableMap<String, Pair<Boolean, Long>> {
        val o = read("outbox.json")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return HashMap()
        val out = HashMap<String, Pair<Boolean, Long>>()
        for (key in o.keys()) o.optJSONArray(key)?.let { out[key] = it.getBoolean(0) to it.getLong(1) }
        return out
    }

    fun saveOutbox(outbox: Map<String, Pair<Boolean, Long>>) {
        val o = JSONObject()
        for ((k, v) in outbox) o.put(k, JSONArray().put(v.first).put(v.second))
        write("outbox.json", o.toString())
    }
}

fun playsFrom(o: JSONObject?): MutableMap<String, Play> {
    val out = HashMap<String, Play>()
    if (o == null) return out
    for (key in o.keys()) o.optJSONArray(key)?.let { out[key] = Play(it.optInt(0), it.optDouble(1, 0.0)) }
    return out
}

fun playsJson(plays: Map<String, Play>): JSONObject {
    val o = JSONObject()
    for ((k, v) in plays) o.put(k, JSONArray().put(v.count).put(v.last))
    return o
}
