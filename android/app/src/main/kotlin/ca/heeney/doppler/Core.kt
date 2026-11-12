package ca.heeney.doppler

import java.security.MessageDigest
import kotlin.random.Random

/** The rules every part of doppler shares; doppler/core.py is the same thing in Python. Keep them in step. */
object Core {
    val AUDIO_EXTS = setOf("flac", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "wv", "ape", "alac", "mka")
    const val LIVE_WINDOW = 45.0
    const val END_SLACK = 3.0
    const val PLAY_AFTER = 240.0

    private val digits = Regex("(\\d+)")

    /** Sorts "2 - x" before "10 - x", case-blind. */
    val natural: Comparator<String> = Comparator { a, b ->
        val pa = split(a)
        val pb = split(b)
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]
            val y = pb[i]
            val c = if (x is Long && y is Long) x.compareTo(y)
            else if (x is Long) -1 else if (y is Long) 1
            else (x as String).compareTo(y as String)
            if (c != 0) return@Comparator c
        }
        pa.size.compareTo(pb.size)
    }

    private fun split(text: String): List<Any> {
        val out = ArrayList<Any>()
        var last = 0
        for (m in digits.findAll(text)) {
            out.add(text.substring(last, m.range.first).lowercase())
            out.add(m.value.toLongOrNull() ?: Long.MAX_VALUE)
            last = m.range.last + 1
        }
        out.add(text.substring(last).lowercase())
        return out
    }

    fun isFolder(playlist: String) = playlist.startsWith("f:")

    /** Every folder with music anywhere under it is a playlist; "f:" is the whole library. */
    fun folders(tracks: Collection<String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        out["f:"] = ArrayList()
        for (track in tracks.sortedWith(natural)) {
            out["f:"]!!.add(track)
            val parts = track.split("/").dropLast(1)
            for (depth in 1..parts.size) out.getOrPut("f:" + parts.take(depth).joinToString("/")) { ArrayList() }.add(track)
        }
        return out
    }

    fun playlistTracks(playlist: String, tracks: Collection<String>, custom: Map<String, CustomPlaylist>): List<String> {
        if (isFolder(playlist)) {
            val prefix = playlist.substring(2)
            if (prefix.isEmpty()) return tracks.sortedWith(natural)
            return tracks.filter { it.startsWith("$prefix/") }.sortedWith(natural)
        }
        val have = tracks.toHashSet()
        return custom[playlist]?.tracks?.filter { it in have } ?: emptyList()
    }

    fun playlistName(playlist: String, custom: Map<String, CustomPlaylist>): String =
        if (isFolder(playlist)) playlist.substring(2).substringAfterLast("/").ifEmpty { "Everything" }
        else custom[playlist]?.name?.ifEmpty { null } ?: "Untitled"

    fun defaultSlots(folderIds: Set<String>): Map<String, String> {
        val top = folderIds.filter { it != "f:" && !it.substring(2).contains("/") }.sortedWith(natural)
        val out = HashMap<String, String>()
        for ((slot, fallback) in listOf("1" to 0, "2" to 1)) {
            if ("f:$slot" in folderIds) out[slot] = "f:$slot"
            else if (top.size > fallback) out[slot] = top[fallback]
        }
        return out
    }

    fun orderHash(order: List<String>): String {
        val sha = MessageDigest.getInstance("SHA-1").digest(order.joinToString("\n").toByteArray())
        return sha.joinToString("") { "%02x".format(it) }.take(12)
    }

    fun newOrder(tracks: List<String>, mode: String, rng: Random, first: String? = null, avoidFirst: String? = null): List<String> {
        val order = tracks.toMutableList()
        if (mode == "order") return order
        order.shuffle(rng)
        if (first != null && first in order) {
            order.remove(first)
            order.add(0, first)
        } else if (avoidFirst != null && order.size > 1 && order[0] == avoidFirst) {
            val swap = rng.nextInt(1, order.size)
            order[0] = order[swap].also { order[swap] = order[0] }
        }
        return order
    }

    data class Fitted(val order: List<String>, val index: Int, val sameTrack: Boolean)

    /** Fit a saved run to what is in the playlist now (see core.reconcile). */
    fun reconcile(order: List<String>, index: Int, tracks: List<String>, mode: String, rng: Random): Fitted {
        if (tracks.isEmpty()) return Fitted(emptyList(), 0, false)
        val current = order.getOrNull(index)
        if (mode == "order") {
            if (current != null && current in tracks) return Fitted(tracks, tracks.indexOf(current), true)
            val later = if (current != null) order.drop(index + 1).filter { it in tracks } else emptyList()
            return Fitted(tracks, if (later.isNotEmpty()) tracks.indexOf(later[0]) else 0, false)
        }
        val have = tracks.toHashSet()
        val kept = ArrayList<String>()
        val seen = HashSet<String>()
        var newIndex = 0
        var same = false
        order.forEachIndexed { i, track ->
            if (i == index) {
                newIndex = kept.size
                same = track in have
            }
            if (track in have && seen.add(track)) kept.add(track)
        }
        if (order.isEmpty() || index >= order.size) newIndex = 0
        if (kept.isEmpty()) return Fitted(newOrder(tracks, mode, rng), 0, false)
        for (track in tracks) {
            if (track in seen) continue
            val low = minOf(newIndex + 1, kept.size)
            kept.add(rng.nextInt(low, kept.size + 1), track)
        }
        if (newIndex >= kept.size) newIndex = 0
        return Fitted(kept, newIndex, same)
    }

    fun livePosition(position: Double, playing: Boolean, device: String, updated: Double, now: Double, me: String): Double {
        if (playing && device != me) {
            val ago = now - updated
            if (ago > 0 && ago < LIVE_WINDOW) return position + ago
        }
        return position
    }

    fun resumePoint(order: List<String>, index: Int, position: Double, duration: Double, sameTrack: Boolean = true): Pair<Int, Double> {
        if (order.isEmpty()) return 0 to 0.0
        val i = index.coerceIn(0, order.size - 1)
        if (!sameTrack) return i to 0.0
        if (duration > 0 && position > duration - END_SLACK) return (i + 1) % order.size to 0.0
        return i to maxOf(0.0, position)
    }

    /** Half of a track, or four minutes of a long one, counts as a play. */
    fun countsAsPlay(heard: Double, duration: Double): Boolean =
        if (duration <= 0) heard >= 30 else heard >= minOf(duration / 2, PLAY_AFTER)

    /** Fold in running play counts, keeping the higher count and the later time; doppler/core.py's
     * merge_plays. Safe to repeat and to receive out of order. Returns whether anything changed. */
    fun mergePlays(into: MutableMap<String, Play>, plays: Map<String, Play>): Boolean {
        var changed = false
        for ((track, p) in plays) {
            val old = into[track]
            val new = if (old == null) p else Play(maxOf(old.count, p.count), maxOf(old.last, p.last))
            if (new != old) { into[track] = new; changed = true }
        }
        return changed
    }

    private val leadingNumber = Regex("^\\d{1,3}\\s*[-.)]\\s*|^\\d{1,3}\\s+(?=\\S)")

    fun splitTitle(trackId: String): Pair<String, String> {
        var name = trackId.substringAfterLast("/")
        if (name.contains(".")) name = name.substringBeforeLast(".")
        val stem = leadingNumber.replaceFirst(name, "").trim().ifEmpty { name }
        val at = stem.indexOf(" - ")
        return if (at >= 0) stem.substring(0, at).trim() to stem.substring(at + 3).trim() else "" to stem
    }
}

/** How often a track has been played, and when last (hub seconds). */
data class Play(val count: Int, val last: Double) : Comparable<Play> {
    override fun compareTo(other: Play) = compareValuesBy(this, other, { it.count }, { it.last })

    companion object {
        val NONE = Play(0, 0.0)
    }
}

data class CustomPlaylist(val id: String, val name: String, val tracks: List<String>)
