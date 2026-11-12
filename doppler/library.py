"""Reading a music folder: which files are there, what their tags say, and their cover art.
Tags come from mutagen when it is installed, then ffprobe, then the file name."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import threading

from . import core

try:  # optional
    import mutagen
except Exception:  # pragma: no cover - depends on the machine
    mutagen = None

ART_SIZES = (160, 640)
COVER_NAMES = ("cover", "folder", "front", "album", "artwork")
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp")


def walk(root: str) -> dict[str, tuple[float, int]]:
    """Every audio file under root as {relative path: (mtime, size)}. Dot-folders are skipped."""
    found = {}
    root = os.path.abspath(root)
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        for name in files:
            if name.startswith(".") or not core.is_audio(name):
                continue
            path = os.path.join(base, name)
            try:
                stat = os.stat(path)
            except OSError:
                continue
            found[os.path.relpath(path, root).replace(os.sep, "/")] = (stat.st_mtime, stat.st_size)
    return found


def _first(value):
    if isinstance(value, (list, tuple)):
        value = value[0] if value else ""
    return str(value).strip() if value is not None else ""


def _number(value) -> int:
    try:
        return int(_first(value).split("/")[0])
    except ValueError:
        return 0


def _has_picture(path: str) -> bool:
    try:
        audio = mutagen.File(path)
    except Exception:
        return False
    if audio is None:
        return False
    if getattr(audio, "pictures", None):
        return True
    tags = getattr(audio, "tags", None)
    if tags is None:
        return False
    try:
        keys = list(tags.keys())
    except Exception:
        return False
    return any(k.startswith("APIC") or k in ("covr", "metadata_block_picture") for k in keys)


def read_tags(path: str, track_id: str) -> dict:
    artist, title = core.split_title(track_id)
    tags = {"title": title, "artist": artist, "album": "", "duration": 0.0, "n": 0, "art": False}
    if mutagen is not None:
        try:
            audio = mutagen.File(path, easy=True)
        except Exception:
            audio = None
        if audio is not None:
            get = lambda key: _first(audio.get(key)) if audio.tags is not None else ""
            tags["title"] = get("title") or tags["title"]
            tags["artist"] = get("artist") or get("albumartist") or tags["artist"]
            tags["album"] = get("album")
            tags["n"] = _number(audio.get("tracknumber")) if audio.tags is not None else 0
            tags["duration"] = round(float(getattr(audio.info, "length", 0) or 0), 2)
            tags["art"] = _has_picture(path)
            return tags
    if shutil.which("ffprobe"):
        try:
            out = subprocess.run(
                ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", path],
                capture_output=True, timeout=20, check=True).stdout
            data = json.loads(out)
            found = {k.lower(): v for k, v in (data.get("format", {}).get("tags") or {}).items()}
            tags["title"] = found.get("title") or tags["title"]
            tags["artist"] = found.get("artist") or found.get("album_artist") or tags["artist"]
            tags["album"] = found.get("album", "")
            tags["n"] = _number(found.get("track"))
            tags["duration"] = round(float(data.get("format", {}).get("duration") or 0), 2)
            tags["art"] = any(s.get("disposition", {}).get("attached_pic") for s in data.get("streams", []))
        except Exception:
            pass
    return tags


def embedded_picture(path: str) -> bytes | None:
    if mutagen is None:
        return None
    try:
        audio = mutagen.File(path)
    except Exception:
        return None
    if audio is None:
        return None
    pictures = getattr(audio, "pictures", None)
    if pictures:
        front = [p for p in pictures if getattr(p, "type", 3) == 3] or pictures
        return bytes(front[0].data)
    tags = getattr(audio, "tags", None)
    if tags is None:
        return None
    try:
        for key in tags.keys():
            if key.startswith("APIC"):
                return bytes(tags[key].data)
        if "covr" in tags and tags["covr"]:
            return bytes(tags["covr"][0])
        if "metadata_block_picture" in tags:
            import base64
            from mutagen.flac import Picture
            return bytes(Picture(base64.b64decode(tags["metadata_block_picture"][0])).data)
    except Exception:
        return None
    return None


def folder_picture(path: str) -> bytes | None:
    """A cover image lying next to the file: cover.jpg and friends, or the only image there."""
    folder = os.path.dirname(path)
    try:
        images = sorted(n for n in os.listdir(folder) if n.lower().endswith(IMAGE_EXTS) and not n.startswith("."))
    except OSError:
        return None
    named = [n for n in images if n.rsplit(".", 1)[0].lower() in COVER_NAMES]
    pick = named[0] if named else (images[0] if len(images) == 1 else None)
    if not pick:
        return None
    try:
        with open(os.path.join(folder, pick), "rb") as handle:
            return handle.read()
    except OSError:
        return None


class Library:
    """The index of one music folder, kept fresh by rescanning when asked and not more than
    every few seconds. Tags are read once per file and remembered until the file changes."""

    def __init__(self, root: str, cache_dir: str, min_interval: float = 15.0):
        self.root = os.path.abspath(root)
        self.cache_dir = cache_dir
        self.min_interval = min_interval
        self.lock = threading.Lock()
        self.scan_lock = threading.Lock()
        self.tracks: dict[str, dict] = {}
        self.rev = ""
        self.last_scan = 0.0
        self._cache_path = os.path.join(cache_dir, "tags.json")
        os.makedirs(os.path.join(cache_dir, "art"), exist_ok=True)
        try:
            with open(self._cache_path) as handle:
                saved = json.load(handle)
            if saved.get("root") == self.root:
                self.tracks = saved["tracks"]
                self._set_rev()
        except (OSError, ValueError, KeyError):
            pass

    def _set_rev(self):
        digest = hashlib.sha1()
        for track_id in sorted(self.tracks):
            entry = self.tracks[track_id]
            digest.update(f"{track_id}\0{entry['m']}\0{entry['s']}\n".encode())
        self.rev = digest.hexdigest()[:12]

    def scan(self, force: bool = False) -> bool:
        """Rescan if it has been a while. Returns True when the library changed.
        An unreadable or empty folder (a drive that isn't mounted) never wipes the index."""
        import time
        if not force and time.time() - self.last_scan < self.min_interval:
            return False
        if not self.scan_lock.acquire(blocking=False):
            return False
        try:
            self.last_scan = time.time()
            if not os.path.isdir(self.root):
                return False
            found = walk(self.root)
            if not found and self.tracks:
                return False
            with self.lock:
                old = self.tracks
            new = {}
            for track_id, (mtime, size) in found.items():
                entry = old.get(track_id)
                if entry and entry["m"] == mtime and entry["s"] == size:
                    new[track_id] = entry
                else:
                    tags = read_tags(os.path.join(self.root, track_id), track_id)
                    tags.update(m=mtime, s=size)
                    new[track_id] = tags
            if new == old:
                return False
            with self.lock:
                self.tracks = new
                self._set_rev()
            tmp = self._cache_path + ".tmp"
            with open(tmp, "w") as handle:
                json.dump({"root": self.root, "tracks": new}, handle)
            os.replace(tmp, self._cache_path)
            return True
        finally:
            self.scan_lock.release()

    def ids(self):
        with self.lock:
            return list(self.tracks)

    def public(self) -> dict:
        with self.lock:
            tracks = [
                {"id": track_id, "title": e["title"], "artist": e["artist"], "album": e["album"],
                 "duration": e["duration"], "n": e["n"], "art": e["art"]}
                for track_id, e in sorted(self.tracks.items(), key=lambda kv: core.natural_key(kv[0]))
            ]
            return {"rev": self.rev, "tracks": tracks}

    def path(self, track_id: str) -> str | None:
        """The file for a track id, or None for anything that reaches outside the library."""
        full = os.path.realpath(os.path.join(self.root, track_id))
        if not full.startswith(os.path.realpath(self.root) + os.sep) or not os.path.isfile(full):
            return None
        return full

    def art(self, track_id: str, size: int) -> bytes | None:
        """Cover art as a JPEG no wider than `size`, from the file's own picture or the folder's."""
        path = self.path(track_id)
        if not path:
            return None
        size = min(ART_SIZES, key=lambda s: abs(s - size))
        stat = os.stat(path)
        key = hashlib.sha1(f"{track_id}\0{stat.st_mtime}\0{size}".encode()).hexdigest()
        cached = os.path.join(self.cache_dir, "art", key + ".jpg")
        missing = cached + ".none"
        if os.path.exists(cached):
            with open(cached, "rb") as handle:
                return handle.read()
        if os.path.exists(missing):
            return None
        raw = embedded_picture(path) or folder_picture(path)
        if raw is None and mutagen is None and shutil.which("ffmpeg"):
            raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-an", "-frames:v", "1", "-f", "image2pipe",
                                  "-vcodec", "mjpeg", "pipe:1"], capture_output=True, timeout=30).stdout or None
        if not raw:
            open(missing, "w").close()
            return None
        out = raw
        if shutil.which("ffmpeg"):
            try:
                done = subprocess.run(
                    ["ffmpeg", "-v", "quiet", "-i", "pipe:0", "-vf",
                     f"scale='min({size},iw)':'min({size},ih)':force_original_aspect_ratio=decrease",
                     "-frames:v", "1", "-q:v", "3", "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1"],
                    input=raw, capture_output=True, timeout=30)
                if done.returncode == 0 and done.stdout:
                    out = done.stdout
            except Exception:
                pass
        tmp = cached + ".tmp"
        with open(tmp, "wb") as handle:
            handle.write(out)
        os.replace(tmp, cached)
        return out
