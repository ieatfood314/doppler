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


def playlist_tracks(playlist: str, track_ids, custom: dict) -> list[str]:
    """The tracks of a folder or of a hand-made playlist, leaving out files that are gone."""
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
