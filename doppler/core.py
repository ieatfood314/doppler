"""The rules every part of doppler shares: what a playlist is, how a shuffle is kept, and how two
devices agree on where a playlist is up to. No I/O in here, so the tests drive it directly."""

from __future__ import annotations

import hashlib
import random
import re

AUDIO_EXTS = frozenset(
    "flac mp3 m4a aac ogg oga opus wav wma aiff aif wv ape alac mka".split()
)

# A session that another device is still playing moves on while we look at it. Past this many
# seconds without a word from that device it has probably gone away, so its last report stands.
LIVE_WINDOW = 45.0
# Resuming this close to the end of a track starts the next one instead.
END_SLACK = 3.0
# A track counts as played once half of it has been heard, or this many seconds of a long one.
PLAY_AFTER = 240.0


def is_audio(name: str) -> bool:
    return name.rsplit(".", 1)[-1].lower() in AUDIO_EXTS if "." in name else False


def natural_key(text: str):
    """Sort '2 - x' before '10 - x', case-blind."""
    return [int(part) if part.isdigit() else part.casefold() for part in re.split(r"(\d+)", text)]


def folder_id(path: str) -> str:
    return "f:" + path


def is_folder(playlist: str) -> bool:
    return playlist.startswith("f:")


def folders(track_ids) -> dict[str, list[str]]:
    """Every folder with music anywhere under it is a playlist of all that music.
    'f:' (the library itself) is everything."""
    out: dict[str, list[str]] = {"f:": []}
    for track in sorted(track_ids, key=natural_key):
        out["f:"].append(track)
        parts = track.split("/")[:-1]
        for depth in range(1, len(parts) + 1):
            out.setdefault(folder_id("/".join(parts[:depth])), []).append(track)
    return out


# An artist is "a:<name>", every track by them or on an album of theirs. An album is
# "b:<album>", or "b:<album>\t<album artist>" when the files name one, so that two bands'
# "Greatest Hits" stay apart while a soundtrack whose tracks name no album artist stays whole.
# Names match whatever their case and spacing.

def artist_id(name: str) -> str:
    return "a:" + name


def album_id(album: str, album_artist: str = "") -> str:
    return "b:" + album + ("\t" + album_artist if album_artist else "")


def is_tagged(playlist: str) -> bool:
    """An artist or an album: a playlist made from tags, which needs them to know its tracks."""
    return playlist.startswith(("a:", "b:"))


def _norm(text) -> str:
    return " ".join(str(text or "").split()).casefold()


def track_artists(meta: dict) -> list[str]:
    """The artists a track belongs to: its own, and its album's when that is someone else."""
    out = []
    for name in (meta.get("artist"), meta.get("albumartist")):
        name = str(name or "").strip()
        if name and _norm(name) not in map(_norm, out):
            out.append(name)
    return out


def track_album(meta: dict) -> str | None:
    album = str(meta.get("album") or "").strip()
    return album_id(album, str(meta.get("albumartist") or "").strip()) if album else None


def _in_tagged(playlist: str, meta: dict) -> bool:
    if playlist.startswith("a:"):
        wanted = _norm(playlist[2:])
        return any(_norm(name) == wanted for name in track_artists(meta))
    album, _, by = playlist[2:].partition("\t")
    return bool(meta.get("album")) and _norm(album) == _norm(meta.get("album")) and _norm(by) == _norm(meta.get("albumartist"))


def album_order(tags: dict):
    """Sort key for a track by its place on its album: album, disc, track number, then file name."""
    def key(track):
        meta = tags.get(track) or {}
        n = int(meta.get("n") or 0)
        return (natural_key(str(meta.get("album") or "")), int(meta.get("disc") or 0), n == 0, n, natural_key(track))
    return key


def tagged(tags: dict) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    """Every artist and every album in the library, as ({id: tracks}, {id: tracks}). Each is named
    the way most of its files spell it (the first of them, on a tie)."""
    groups: dict[tuple, list[str]] = {}
    spellings: dict[tuple, dict[str, int]] = {}

    def add(key, playlist, track):
        groups.setdefault(key, []).append(track)
        seen = spellings.setdefault(key, {})
        seen[playlist] = seen.get(playlist, 0) + 1

    for track in sorted(tags, key=album_order(tags)):
        meta = tags[track] or {}
        for name in track_artists(meta):
            add(("a", _norm(name)), artist_id(name), track)
        album = track_album(meta)
        if album:
            add(("b", _norm(meta.get("album")), _norm(meta.get("albumartist"))), album, track)
    artists: dict[str, list[str]] = {}
    albums: dict[str, list[str]] = {}
    for key, tracks in groups.items():
        seen = spellings[key]
        playlist = max(seen, key=seen.get)
        (artists if key[0] == "a" else albums)[playlist] = tracks
    return artists, albums


def album_by(playlist: str, tracks, tags: dict) -> str:
    """Who an album is by: its album artist, or the one artist on all of it, or "Various artists"."""
    if "\t" in playlist:
        return playlist.split("\t", 1)[1]
    names = {}
    for track in tracks:
        name = str((tags.get(track) or {}).get("artist") or "").strip()
        if name:
            names.setdefault(_norm(name), name)
    return next(iter(names.values())) if len(names) == 1 else ("Various artists" if names else "")


def playlist_tracks(playlist: str, track_ids, custom: dict, tags: dict | None = None) -> list[str]:
    """The tracks of a folder, an artist, an album or a hand-made playlist, leaving out files
    that are gone. Artists and albums need the tags: {track: {"artist", "albumartist", ...}}."""
    if is_tagged(playlist):
        tags = tags or {}
        return sorted((t for t in track_ids if t in tags and _in_tagged(playlist, tags[t] or {})), key=album_order(tags))
    if is_folder(playlist):
        prefix = playlist[2:]
        if not prefix:
            return sorted(track_ids, key=natural_key)
        prefix += "/"
        return sorted((t for t in track_ids if t.startswith(prefix)), key=natural_key)
    have = set(track_ids)
    entry = custom.get(playlist) or {}
    return [t for t in entry.get("tracks", []) if t in have]


def playlist_name(playlist: str, custom: dict) -> str:
    if is_folder(playlist):
        return playlist[2:].rsplit("/", 1)[-1] or "Everything"
    if is_tagged(playlist):
        return playlist[2:].split("\t", 1)[0]
    return (custom.get(playlist) or {}).get("name") or "Untitled"


def default_slots(folder_ids) -> dict[str, str]:
    """Slots 1 and 2 are the folders called 1 and 2 when they exist, otherwise the first two."""
    top = sorted((f for f in folder_ids if f != "f:" and "/" not in f), key=natural_key)
    slots = {}
    for slot, fallback in (("1", 0), ("2", 1)):
        if folder_id(slot) in folder_ids:
            slots[slot] = folder_id(slot)
        elif len(top) > fallback:
            slots[slot] = top[fallback]
    return slots


def order_hash(order) -> str:
    return hashlib.sha1("\n".join(order).encode()).hexdigest()[:12]


def new_order(tracks, mode: str, rng: random.Random, first: str | None = None, avoid_first: str | None = None):
    """A fresh run through a playlist. `first` puts a chosen track at the start;
    `avoid_first` keeps a reshuffle from opening with the track that just played."""
    order = list(tracks)
    if mode == "order":
        return order
    rng.shuffle(order)
    if first in order:
        order.remove(first)
        order.insert(0, first)
    elif avoid_first and len(order) > 1 and order[0] == avoid_first:
        swap = rng.randrange(1, len(order))
        order[0], order[swap] = order[swap], order[0]
    return order


def reconcile(order, index: int, tracks, mode: str, rng: random.Random):
    """Fit a saved run to what is in the playlist now. Returns (order, index, same_track):
    same_track is False when the track the run was on has gone, so its position means nothing."""
    tracks = list(tracks)
    if not tracks:
        return [], 0, False
    current = order[index] if 0 <= index < len(order) else None
    if mode == "order":
        if current in tracks:
            return tracks, tracks.index(current), True
        # Land on whatever now sits where the missing track was.
        later = [t for t in order[index + 1:] if t in tracks] if current else []
        return tracks, (tracks.index(later[0]) if later else 0), False
    have = set(tracks)
    kept, new_index, same = [], 0, False
    seen = set()
    for i, track in enumerate(order):
        if i == index:
            new_index = len(kept)
            same = track in have
        if track in have and track not in seen:
            kept.append(track)
            seen.add(track)
    if not order or index >= len(order):
        new_index = 0
    added = [t for t in tracks if t not in seen]
    if not kept:
        return new_order(tracks, mode, rng), 0, False
    for track in added:
        # New music lands somewhere in what is still to come, so it turns up this time round.
        kept.insert(rng.randint(min(new_index + 1, len(kept)), len(kept)), track)
    if new_index >= len(kept):
        new_index = 0
    return kept, new_index, same


def live_position(session: dict, now: float, device: str) -> float:
    """Where a session is right now. One that another device is playing has moved on since
    it last reported; one that has gone quiet is taken at its word."""
    position = float(session.get("position") or 0)
    if session.get("playing") and session.get("device") != device:
        ago = now - float(session.get("updated") or 0)
        if 0 < ago < LIVE_WINDOW:
            position += ago
    return position


def resume_point(order, index: int, position: float, duration: float | None, same_track: bool = True):
    """Where to start: the saved spot, or the next track when the saved spot is at the very end."""
    if not order:
        return 0, 0.0
    index = min(max(index, 0), len(order) - 1)
    if not same_track:
        return index, 0.0
    if duration and position > duration - END_SLACK:
        return (index + 1) % len(order), 0.0
    return index, max(0.0, position)


def split_title(track_id: str) -> tuple[str, str]:
    """Artist and title from a file name, for files with no tags: 'Artist - Title.flac'."""
    name = track_id.rsplit("/", 1)[-1]
    name = name.rsplit(".", 1)[0] if "." in name else name
    stem = re.sub(r"^\d{1,3}\s*[-.)]\s*|^\d{1,3}\s+(?=\S)", "", name).strip() or name
    if " - " in stem:
        artist, title = stem.split(" - ", 1)
        return artist.strip(), title.strip()
    return "", stem


def clamp_index(index, count: int) -> int:
    try:
        index = int(index)
    except (TypeError, ValueError):
        return 0
    return min(max(index, 0), max(count - 1, 0))


def counts_as_play(heard: float, duration: float) -> bool:
    """Whether this much of a track is enough to call it played: half of it, or four minutes."""
    if duration <= 0:
        return heard >= 30
    return heard >= min(duration / 2, PLAY_AFTER)


def merge_plays(into: dict, plays: dict) -> bool:
    """Fold play counts in, keeping the higher count and the later time for each track. Every device
    sends its own running totals, so this is safe to repeat and to receive in any order.
    Both sides are {track: [count, last played]}. Returns whether anything changed."""
    changed = False
    for track, value in plays.items():
        try:
            count, last = int(value[0]), float(value[1])
        except (TypeError, ValueError, IndexError):
            continue
        old = into.get(track)
        new = [max(count, old[0]), max(last, old[1])] if old else [count, last]
        if new != old:
            into[str(track)] = new
            changed = True
    return changed


def total_plays(sources: dict) -> dict:
    """Plays across every device: the counts add up, the last time is the latest."""
    out: dict = {}
    for plays in sources.values():
        for track, (count, last) in plays.items():
            old = out.get(track)
            out[track] = [old[0] + count, max(old[1], last)] if old else [count, last]
    return out


# --- listening time, month by month

def add_listening(into: dict, month: str, track: str, seconds: float = 0.0, plays: int = 0):
    """Add to a device's running totals: {month: {track: [seconds heard, plays]}}. Months are the
    device's own local "YYYY-MM"."""
    entry = into.setdefault(month, {}).setdefault(track, [0.0, 0])
    entry[0] = round(entry[0] + max(0.0, seconds), 1)
    entry[1] += plays


def merge_listening(into: dict, listened: dict) -> bool:
    """Fold a device's running totals in, keeping the larger of each. Like play counts, every device
    sends absolute totals, so this is safe to repeat and to receive in any order."""
    changed = False
    if not isinstance(listened, dict):
        return False
    for month, tracks in listened.items():
        if not isinstance(tracks, dict) or not re.fullmatch(r"\d{4}-\d{2}", str(month)):
            continue
        for track, value in tracks.items():
            try:
                seconds, plays = float(value[0]), int(value[1])
            except (TypeError, ValueError, IndexError):
                continue
            mine = into.setdefault(str(month), {})
            old = mine.get(track)
            new = [max(seconds, old[0]), max(plays, old[1])] if old else [seconds, plays]
            if new != old:
                mine[str(track)] = new
                changed = True
    return changed


def _heard(sources: dict) -> dict[str, dict]:
    """Every device's listening added together: {month: {track: [seconds, plays]}}."""
    months: dict[str, dict] = {}
    for listened in sources.values():
        for month, tracks in listened.items():
            into = months.setdefault(month, {})
            for track, (seconds, plays) in tracks.items():
                old = into.get(track) or [0.0, 0]
                into[track] = [old[0] + seconds, old[1] + plays]
    return months


def _by_artist(tracks: dict, tags: dict) -> list[list]:
    """[name, seconds, the track of theirs heard most] for each artist, most heard first. The artist is
    the track's artist tag, else its album artist, else the "Artist - Title" in its file name."""
    artists: dict[str, list] = {}
    for track, (seconds, _) in tracks.items():
        meta = tags.get(track) or {}
        name = str(meta.get("artist") or meta.get("albumartist") or "").strip() or split_title(track)[0]
        if name:
            entry = artists.setdefault(_norm(name), [name, 0.0, track, -1.0])
            entry[1] += seconds
            if seconds > entry[3]:
                entry[2:] = [track, seconds]
    return [e[:3] for e in sorted(artists.values(), key=lambda e: (-e[1], e[0]))]


def _songs(tracks: dict) -> int:
    """Different songs heard: played, or heard for half a minute."""
    return sum(1 for s, p in tracks.values() if p or s >= 30)


def listening_months(sources: dict, tags: dict, top: int = 5) -> list[dict]:
    """Every month anyone listened, newest first: time heard and songs played on every device
    together, how many different songs, and the artists and songs heard most."""
    months = _heard(sources)
    out = []
    for month in sorted(months, reverse=True):
        tracks = months[month]
        songs = sorted(tracks.items(), key=lambda kv: (-kv[1][0], kv[0]))
        out.append({
            "month": month,
            "seconds": round(sum(s for s, _ in tracks.values())),
            "plays": sum(p for _, p in tracks.values()),
            "songs": _songs(tracks),
            "artists": [[n, round(s)] for n, s, _ in _by_artist(tracks, tags)[:top]],
            "top": [[t, round(s), p] for t, (s, p) in songs[:top]],
        })
    return out


def year_in_music(sources: dict, tags: dict, year: int, top: int = 5) -> dict:
    """The year's listening on every device together, for the story at the end of it: time heard, month
    by month, how many songs and artists, and the artists (with the track of theirs heard most) and
    songs heard most."""
    tracks: dict[str, list] = {}
    months = [0.0] * 12
    for month, heard in _heard(sources).items():
        if not month.startswith(f"{year:04d}-"):
            continue
        for track, (seconds, plays) in heard.items():
            old = tracks.get(track) or [0.0, 0]
            tracks[track] = [old[0] + seconds, old[1] + plays]
            months[int(month[5:7]) - 1] += seconds
    artists = _by_artist(tracks, tags)
    songs = sorted(tracks.items(), key=lambda kv: (-kv[1][0], -kv[1][1], kv[0]))
    return {
        "year": year,
        "seconds": round(sum(months)),
        "plays": sum(p for _, p in tracks.values()),
        "songs": _songs(tracks),
        "artistCount": len(artists),
        "months": [round(s) for s in months],
        "artists": [[n, round(s), t] for n, s, t in artists[:top]],
        "top": [[t, round(s), p] for t, (s, p) in songs[:top]],
    }
