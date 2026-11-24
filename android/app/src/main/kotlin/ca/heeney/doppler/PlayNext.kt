package ca.heeney.doppler

/** A track's old and new places, and the run after putting it immediately after the current one. */
internal data class PlayNextMove(val order: List<String>, val from: Int, val to: Int)

internal fun playNextMove(order: List<String>, current: Int, track: String): PlayNextMove? {
    if (current !in order.indices) return null
    val from = order.indexOf(track)
    if (from < 0 || from == current || from == current + 1) return null
    // Moving a song from before the current one shifts the current one down a place first.
    val to = if (from < current) current else current + 1
    val changed = order.toMutableList()
    changed.add(to, changed.removeAt(from))
    return PlayNextMove(changed, from, to)
}
