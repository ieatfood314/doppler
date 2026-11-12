"""The desktop player: a background process with no window. It plays through mpv, keeps the hub
up to date, hands over when another device starts playing, and answers the `doppler` command,
the media keys (MPRIS) and the window when it is open."""

from __future__ import annotations

import json
import os
import queue
import random
import shutil
import socket as socketlib
import subprocess
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote, urlparse

from . import __version__, core
from .mpv import Mpv

HEARTBEAT = 15.0          # seconds between position reports while playing
MPV_LINGER = 20 * 60.0    # a paused mpv is shut down after this long; the spot is kept
DEFAULT_PORT = 4636


def config_path() -> str:
    return os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "doppler", "config.json")


def load_config() -> dict:
    config = {"hub": "", "device": socketlib.gethostname().split(".")[0], "root": "", "eq": {}, "port": DEFAULT_PORT}
    try:
        with open(config_path()) as handle:
            config.update(json.load(handle))
    except (OSError, ValueError):
        pass
    config["hub"] = config["hub"].rstrip("/")
    return config


def save_config(config: dict):
    path = config_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as handle:
        json.dump(config, handle, indent=2)
        handle.write("\n")


class HubClient:
    def __init__(self, base: str):
        self.base = base

    def request(self, method: str, path: str, body=None, timeout: float = 4.0):
        if not self.base:
            raise OSError("no hub set")
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method,
                                         headers={"Content-Type": "application/json"} if data else {})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read() or b"null")
        except urllib.error.HTTPError as error:
            try:
                return json.loads(error.read())
            except ValueError:
                raise OSError(f"hub answered {error.code}")
        except (urllib.error.URLError, TimeoutError, ValueError, ConnectionError) as error:
            raise OSError(str(error))

    def get(self, path: str, timeout: float = 4.0):
        return self.request("GET", path, timeout=timeout)

    def file_url(self, track: str) -> str:
        return self.base + "/file/" + quote(track)

    def art_url(self, track: str, size: int = 640) -> str:
        return f"{self.base}/art/{quote(track)}?s={size}"


class Player:
    def __init__(self, config: dict, state_dir: str, runtime_dir: str, mpv_extra=(), rng: random.Random | None = None):
        self.config = config
        self.device = config["device"]
        self.hub = HubClient(config["hub"])
        self.rng = rng or random.Random()
        self.state_dir = state_dir
        self.runtime_dir = runtime_dir
        os.makedirs(state_dir, exist_ok=True)
        os.makedirs(runtime_dir, exist_ok=True)
        # SimpleQueue because the SIGTERM handler puts into it on the main thread, which may be holding a
        # Queue's lock right then; a Queue deadlocks there, a SimpleQueue's put is safe in a signal handler.
        self.events: queue.SimpleQueue = queue.SimpleQueue()
        self.mpv = Mpv(os.path.join(runtime_dir, "mpv.sock"), lambda e: self.events.put(("mpv", e)), mpv_extra)
        self.library: dict[str, dict] = {}
        self.library_rev = ""
        self.hub_state: dict | None = None
        self.hub_online = False
        self.hub_offset = 0.0
        self.sessions: dict[str, dict] = {}
        self.current: str | None = None
        self.order: list[str] = []
        self.index = 0
        self.playing = False
        self.paused_at: float | None = None
        self.claimed_at = 0.0
        self.started = False
        self.ended = 0
        self.eq_applied: str | None = None
        self.message = ""
        self.target: float | None = None   # where a load or seek is heading, until mpv gets there
        self.ui_rev = 1
        self.ui_changed = threading.Condition()
        self.mpris = None
        self._outbox: dict[str, dict] = {}
        self._outbox_ready = threading.Condition()
        self._dir_cache: dict[str, tuple[float, set]] = {}
        self._load_saved()

    # --- small things

    def hub_now(self) -> float:
        return time.time() + self.hub_offset

    def _load_saved(self):
        try:
            with open(os.path.join(self.state_dir, "player.json")) as handle:
                saved = json.load(handle)
            self.sessions = saved.get("sessions", {})
            self.library = saved.get("library", {})
            self.library_rev = saved.get("library_rev", "")
            self.hub_state = saved.get("hub_state")
            last = saved.get("current")
            if last in self.sessions:
                self.current = last
                self.order = self.sessions[last]["order"]
                self.index = self.sessions[last]["index"]
        except (OSError, ValueError, KeyError):
            pass

    def _save(self):
        tmp = os.path.join(self.state_dir, "player.json.tmp")
        with open(tmp, "w") as handle:
            json.dump({"sessions": self.sessions, "library": self.library, "library_rev": self.library_rev,
                       "hub_state": self.hub_state, "current": self.current}, handle)
        os.replace(tmp, os.path.join(self.state_dir, "player.json"))

    def bump(self):
        with self.ui_changed:
            self.ui_rev += 1
            self.ui_changed.notify_all()
        if self.mpris:
            self.mpris.update(self.mpris_state())

    def scan_local(self):
        """With no hub, the library is just the folder on this machine, named from its files."""
        from .library import walk
        root = self.config.get("root")
        if not root or not os.path.isdir(root):
            return
        found = walk(root)
        if found and set(found) != set(self.library):
            library = {}
            for track in found:
                artist, title = core.split_title(track)
                library[track] = {"id": track, "title": title, "artist": artist, "album": "", "duration": 0, "art": False}
            self.library = library
            self.bump()

    def custom(self) -> dict:
        return {p["id"]: p for p in (self.hub_state or {}).get("playlists", [])}

    def modes(self) -> dict:
        return (self.hub_state or {}).get("modes", {})

    def slots(self) -> dict:
        slots = dict(core.default_slots(set(core.folders(self.library))))
        slots.update((self.hub_state or {}).get("slots", {}))
        return slots

    def tracks_of(self, playlist: str) -> list[str]:
        return core.playlist_tracks(playlist, list(self.library), self.custom())

    def duration(self, track: str) -> float:
        return float((self.library.get(track) or {}).get("duration") or 0)

    def position(self) -> float:
        if self.target is not None:
            return self.target
        if self.current and self.mpv.alive():
            value = self.mpv.get("time-pos")
            if isinstance(value, (int, float)):
                return max(0.0, float(value))
        session = self.sessions.get(self.current or "")
        return float(session["position"]) if session else 0.0

    def locate(self, track: str) -> str:
        """The copy on this machine if there is one, otherwise the hub's."""
        root = self.config.get("root")
        if root:
            folder, _, name = track.rpartition("/")
            full_dir = os.path.join(root, folder)
            cached = self._dir_cache.get(full_dir)
            if not cached or time.monotonic() - cached[0] > 30:
                try:
                    cached = (time.monotonic(), set(os.listdir(full_dir)))
                except OSError:
                    cached = (time.monotonic(), set())
                self._dir_cache[full_dir] = cached
            if name in cached[1]:
                return os.path.join(root, track)
        return self.hub.file_url(track)

    # --- talking to the hub

    def _report(self, claim: bool = False):
        """Note where we are, and queue it for the hub."""
        if not self.current:
            return
        position = self.position()
        session = {
            "playlist": self.current, "order": self.order, "orderHash": core.order_hash(self.order),
            "index": self.index, "position": position, "playing": self.playing, "device": self.device,
            "updated": self.hub_now(), "unsent": True,
        }
        self.sessions[self.current] = session
        with self._outbox_ready:
            waiting = self._outbox.get(self.current)
            self._outbox[self.current] = {"session": session, "claim": claim or bool(waiting and waiting["claim"]),
                                          "at": time.monotonic()}
            self._outbox_ready.notify()
        self._save()
        self.bump()

    def _sender(self):
        """Deliver reports in the background so a slow network never holds up playback."""
        while True:
            with self._outbox_ready:
                while not self._outbox:
                    self._outbox_ready.wait()
                playlist, item = next(iter(self._outbox.items()))
                del self._outbox[playlist]
            session = item["session"]
            body = {k: session[k] for k in ("playlist", "index", "position", "playing", "orderHash")}
            body.update(device=self.device, claim=item["claim"], age=time.monotonic() - item["at"])
            if item["claim"]:
                body["order"] = session["order"]
            try:
                reply = self.hub.request("POST", "/api/session", body)
                if reply.get("needOrder"):
                    body["order"] = session["order"]
                    body["age"] = time.monotonic() - item["at"]
                    reply = self.hub.request("POST", "/api/session", body)
            except OSError:
                # Keep it for the next report to carry, unless a newer one is already waiting.
                with self._outbox_ready:
                    newer = self._outbox.setdefault(playlist, item)
                    newer["claim"] = newer["claim"] or item["claim"]
                self.events.put(("hub-offline", None))
                time.sleep(5)
                with self._outbox_ready:
                    self._outbox_ready.notify()
                continue
            self.events.put(("reported", (playlist, session, reply)))

    def _watcher(self):
        """Hold a request open on the hub; it answers the moment anything changes."""
        rev, wait = 0, 2.0
        while True:
            try:
                state = self.hub.get(f"/api/wait?rev={rev}&timeout=50", timeout=65) if rev else self.hub.get("/api/state")
                library = None
                if state["library"]["rev"] != self.library_rev:
                    library = self.hub.get("/api/library", timeout=20)
                rev, wait = state["rev"], 2.0
                self.events.put(("hub", (state, library, time.time())))
            except (OSError, KeyError, TypeError):
                self.events.put(("hub-offline", None))
                rev = 0
                time.sleep(wait)
                wait = min(wait * 2, 60)

    def _on_hub(self, state: dict, library: dict | None, received: float):
        self.hub_offset = state["now"] - received
        self.hub_online = True
        self.hub_state = state
        if library:
            self.library = {t["id"]: t for t in library["tracks"]}
            self.library_rev = library["rev"]
            self._save()
        active = state.get("active")
        if self.playing and active and active["device"] != self.device and active["since"] > self.claimed_at:
            self._yield_to(active["device"])
        self.bump()

    def _yield_to(self, device: str):
        """Another device has started playing: stop here, quietly, and leave its session alone."""
        self.playing = False
        self.paused_at = time.monotonic()
        try:
            self.mpv.set("pause", True)
        except RuntimeError:
            pass
        if self.current in self.sessions:
            self.sessions[self.current]["unsent"] = False
        with self._outbox_ready:
            self._outbox.pop(self.current, None)
        self.message = f"Playing on {device} now"
        self.bump()

    def _on_reported(self, playlist: str, session: dict, reply: dict):
        self.hub_online = True
        if reply.get("ok"):
            if self.sessions.get(playlist) is session:
                session["unsent"] = False
            return
        if reply.get("superseded") and playlist == self.current and self.playing:
            self._yield_to((reply.get("active") or {}).get("device", "another device"))
        elif self.sessions.get(playlist) is session:
            session["unsent"] = False

    # --- playing

    def _fetch_session(self, playlist: str):
        """The hub's view of a playlist, or None when it can't be reached in time."""
        have = (self.sessions.get(playlist) or {}).get("orderHash", "")
        try:
            sent = time.time()
            reply = self.hub.get(f"/api/session?playlist={quote(playlist)}&have={have}", timeout=1.5)
        except OSError:
            return None
        self.hub_offset = reply["now"] - (sent + time.time()) / 2
        remote = reply.get("session")
        if remote and "order" not in remote:
            remote["order"] = self.sessions[playlist]["order"]
        return remote

    def play(self, playlist: str, track: str | None = None, fresh: bool = False):
        """Start a playlist where it was left, on whichever device that was."""
        if not self.hub.base:
            self.scan_local()
        tracks = self.tracks_of(playlist)
        if not tracks:
            self.message = "Nothing to play there"
            self.bump()
            return {"ok": False, "error": "that playlist has no tracks on this device"}
        mode = self.modes().get(playlist, "shuffle")
        base = None
        if not fresh:
            remote = self._fetch_session(playlist)
            local = self.sessions.get(playlist)
            if remote and local and local.get("unsent") and local["updated"] > remote["updated"]:
                base = local
            else:
                base = remote or local
        if base and base.get("order"):
            position = core.live_position(base, self.hub_now(), self.device)
            order, index, same = core.reconcile(base["order"], base["index"], tracks, mode, self.rng)
            if track in order:
                index, position = order.index(track), 0.0
            else:
                index, position = core.resume_point(order, index, position, self.duration(order[index]), same)
        else:
            order = core.new_order(tracks, mode, self.rng, first=track)
            index, position = (order.index(track) if track in order else 0), 0.0
        self._quiet_others()
        here = self.position()
        same_list = self.current == playlist and self.order == order and self.mpv.alive() \
            and self.mpv.get("playlist-count") == len(order)
        try:
            if same_list and self.index == index and abs(here - position) < 3 and not track:
                self.mpv.set("pause", False)
            elif same_list:
                self.target = position
                if self.index != index or track:
                    self.index = index
                    self.mpv.set("start", f"{position:.2f}" if position > 0.5 else "none")
                    self.mpv.command("playlist-play-index", index)
                else:
                    self.mpv.command("seek", position, "absolute")
                self.mpv.set("pause", False)
            else:
                self.target = position
                self._load(playlist, order, index, position)
        except RuntimeError as error:
            self.target = None
            self.message = str(error)
            self.bump()
            return {"ok": False, "error": str(error)}
        self.playing = True
        self.paused_at = None
        self.message = ""
        self.claimed_at = self.hub_now()
        self._apply_eq(playlist)
        self._report(claim=True)
        return {"ok": True}

    def _load(self, playlist: str, order: list[str], index: int, position: float):
        self.mpv.start()
        listing = os.path.join(self.runtime_dir, "queue.m3u")
        with open(listing, "w") as handle:
            handle.write("\n".join(self.locate(t) for t in order) + "\n")
        self.current, self.order, self.index = playlist, order, index
        self.started, self.ended = False, 0
        self.mpv.set("pause", False)
        self.mpv.set("playlist-start", index)
        self.mpv.set("start", f"{position:.2f}" if position > 0.5 else "none")
        self.mpv.command("loadlist", listing, "replace")

    def _quiet_others(self):
        """cmus is what doppler replaced here; if it is still playing, pause it rather than play over it."""
        runtime = os.environ.get("XDG_RUNTIME_DIR", "")
        if runtime and os.path.exists(os.path.join(runtime, "cmus-socket")) and shutil.which("cmus-remote"):
            subprocess.run(["cmus-remote", "-U"], capture_output=True, timeout=2)

    def _apply_eq(self, playlist: str):
        """Load the EasyEffects preset tied to a playlist, if the config names one."""
        preset = (self.config.get("eq") or {}).get(playlist)
        if not preset or preset == self.eq_applied or not shutil.which("easyeffects"):
            return
        self.eq_applied = preset

        def run():
            try:
                if subprocess.run(["pgrep", "-x", "easyeffects"], capture_output=True).returncode != 0:
                    subprocess.Popen(["easyeffects", "--gapplication-service"], stdin=subprocess.DEVNULL,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
                    time.sleep(1.0)
                subprocess.run(["easyeffects", "-l", preset], capture_output=True, timeout=10)
            except Exception:
                pass

        threading.Thread(target=run, daemon=True).start()

    def pause(self):
        if not self.playing:
            return {"ok": True}
        self.playing = False
        self.paused_at = time.monotonic()
        try:
            self.mpv.set("pause", True)
        except RuntimeError:
            pass
        self._report()
        return {"ok": True}

    def resume(self):
        """Play again: the playlist this device last had, else whichever played last anywhere, else slot 1."""
        if self.playing:
            return {"ok": True}
        playlist = self.current
        if not playlist:
            active = (self.hub_state or {}).get("active")
            playlist = active["playlist"] if active else self.slots().get("1")
        if not playlist:
            return {"ok": False, "error": "nothing to play yet"}
        return self.play(playlist)

    def toggle(self):
        return self.pause() if self.playing else self.resume()

    def slot(self, number: str):
        """The 1 and 2 keys: play that playlist, or pause it if it is what's playing here."""
        playlist = self.slots().get(str(number))
        if not playlist:
            return {"ok": False, "error": f"no playlist is set for {number}"}
        if self.playing and self.current == playlist:
            return self.pause()
        return self.play(playlist)

    def step(self, by: int):
        if not self.current or not self.order:
            return self.resume()
        if not self.mpv.alive() or not self.playing:
            # Not loaded: move the saved spot, then play from there.
            session = self.sessions.get(self.current)
            if session:
                session["index"] = (session["index"] + by) % len(session["order"])
                session["position"] = 0.0
                session["updated"] = self.hub_now()
                session["unsent"] = True
                session["playing"] = False
                session["device"] = self.device
            return self.play(self.current)
        if by < 0 and self.position() > 4:
            return self.seek(0.0)
        target = self.index + by
        if target >= len(self.order):
            self._wrap()
        else:
            self.target = 0.0
            self.mpv.set("start", "none")
            self.mpv.command("playlist-play-index", max(target, 0))
        return {"ok": True}

    def seek(self, position: float):
        if not self.current or not self.mpv.alive():
            return {"ok": False, "error": "nothing is loaded"}
        position = max(0.0, float(position))
        try:
            self.mpv.command("seek", position, "absolute")
        except RuntimeError:
            return {"ok": False, "error": "that track is still loading"}
        self.target = position
        self._report()
        return {"ok": True}

    def _wrap(self):
        """The end of a run: shuffle again (or start over, for a playlist played in order)."""
        playlist = self.current
        tracks = self.tracks_of(playlist)
        if not tracks:
            self.playing = False
            self._report()
            return
        mode = self.modes().get(playlist, "shuffle")
        last = self.order[self.index] if 0 <= self.index < len(self.order) else None
        order = core.new_order(tracks, mode, self.rng, avoid_first=last)
        self.target = 0.0
        self._load(playlist, order, 0, 0.0)
        self._report(claim=True)

    def _on_mpv(self, event: dict):
        name = event.get("event")
        if name == "doppler-mpv-gone":
            self.target = None
            if self.playing:
                self.playing = False
                self.paused_at = time.monotonic()
                self.bump()
            return
        if name == "playback-restart":
            self.target = None
        elif name == "file-loaded":
            self.started = True
            # `start` was for the track we resumed into; the ones after it begin at the beginning.
            try:
                self.mpv.set("start", "none")
            except RuntimeError:
                pass
        elif name == "end-file":
            self.ended += 1
            if event.get("reason") == "error":
                self.target = None
                try:
                    self.mpv.set("start", "none")
                except RuntimeError:
                    pass
        elif name == "property-change":
            prop, value = event.get("name"), event.get("data")
            if prop == "playlist-pos" and isinstance(value, int) and value >= 0 and self.current:
                if value != self.index and value < len(self.order):
                    self.index = value
                    if self.target is None:
                        self.target = 0.0
                    self._report()
            elif prop == "pause" and self.current and isinstance(value, bool):
                if (not value) != self.playing and self.started:
                    self.playing = not value
                    self.paused_at = None if self.playing else time.monotonic()
                    self._report(claim=self.playing)
            elif prop == "idle-active" and value is True and self.current and self.playing and self.ended:
                if not self.started:
                    self.playing = False
                    self.message = "None of those files could be opened"
                    self._report()
                else:
                    self._wrap()

    # --- the loop

    def call(self, function, *args, timeout: float = 10.0):
        """Run something on the player's own thread and wait for its answer."""
        done = threading.Event()
        box = {}
        self.events.put(("call", (function, args, box, done)))
        if not done.wait(timeout):
            return {"ok": False, "error": "the player is busy"}
        if "error" in box:
            raise box["error"]
        return box.get("result")

    def run(self):
        if self.hub.base:
            threading.Thread(target=self._sender, daemon=True).start()
            threading.Thread(target=self._watcher, daemon=True).start()
        else:
            self.scan_local()
        next_beat = time.monotonic() + HEARTBEAT
        while True:
            try:
                kind, data = self.events.get(timeout=max(0.05, next_beat - time.monotonic()) if self.playing else 60)
            except queue.Empty:
                kind = None
            try:
                if kind == "call":
                    function, args, box, done = data
                    try:
                        box["result"] = function(*args)
                    except Exception as error:  # hand it to the caller
                        box["error"] = error
                    done.set()
                elif kind == "mpv":
                    self._on_mpv(data)
                elif kind == "hub":
                    self._on_hub(*data)
                elif kind == "hub-offline":
                    if self.hub_online:
                        self.hub_online = False
                        self.bump()
                elif kind == "reported":
                    self._on_reported(*data)
                elif kind == "quit":
                    break
                now = time.monotonic()
                if self.playing and now >= next_beat:
                    self._report()
                if now >= next_beat or not self.playing:
                    next_beat = now + HEARTBEAT
                if not self.playing and self.paused_at and self.mpv.alive() and now - self.paused_at > MPV_LINGER:
                    if self.current in self.sessions:
                        self.sessions[self.current]["position"] = self.position()
                    self.mpv.stop()
                    self.paused_at = None
            except Exception as error:
                self.message = f"{type(error).__name__}: {error}"
        self.mpv.stop()

    # --- what the window and the command see

    def _track_view(self, track: str | None):
        if not track:
            return None
        meta = self.library.get(track) or {}
        artist, title = core.split_title(track)
        return {"id": track, "title": meta.get("title") or title, "artist": meta.get("artist") or artist,
                "album": meta.get("album", ""), "duration": meta.get("duration", 0),
                "art": self.hub.art_url(track) if meta.get("art") and self.hub.base else ""}

    def view(self) -> dict:
        state = self.hub_state or {}
        custom = self.custom()
        slots = self.slots()
        by_slot = {v: k for k, v in slots.items()}
        remote_sessions = state.get("sessions", {})
        playlists = []
        folder_map = core.folders(self.library)
        for playlist in sorted(folder_map, key=lambda p: core.natural_key(p[2:])):
            playlists.append((playlist, "folder", len(folder_map[playlist])))
        for playlist, entry in custom.items():
            playlists.append((playlist, "custom", len(self.tracks_of(playlist))))
        out = []
        for playlist, kind, count in playlists:
            session = self.sessions.get(playlist) if (self.sessions.get(playlist) or {}).get("unsent") else None
            session = session or remote_sessions.get(playlist) or self.sessions.get(playlist)
            where = None
            if session:
                track = session.get("track") or (session["order"][session["index"]] if session.get("order") else None)
                where = {"device": session["device"], "playing": session["playing"],
                         "index": session["index"], "track": self._track_view(track)}
            out.append({"id": playlist, "kind": kind, "name": core.playlist_name(playlist, custom),
                        "path": playlist[2:] if kind == "folder" else "", "count": count,
                        "slot": by_slot.get(playlist, ""), "mode": self.modes().get(playlist, "shuffle"),
                        "session": where})
        active = state.get("active")
        elsewhere = None
        if active and active["device"] != self.device:
            session = remote_sessions.get(active["playlist"])
            if session and session["device"] == active["device"]:
                elsewhere = {"device": active["device"], "playlist": active["playlist"],
                             "name": core.playlist_name(active["playlist"], custom),
                             "playing": session["playing"], "track": self._track_view(session.get("track"))}
        track = self.order[self.index] if self.current and 0 <= self.index < len(self.order) else None
        return {
            "rev": self.ui_rev, "version": __version__, "device": self.device,
            "hub": {"url": self.hub.base, "online": self.hub_online},
            "playing": self.playing, "playlist": self.current or "",
            "playlistName": core.playlist_name(self.current, custom) if self.current else "",
            "track": self._track_view(track), "index": self.index, "count": len(self.order),
            "position": self.position(), "at": time.time(),
            "slots": slots, "playlists": out, "elsewhere": elsewhere, "message": self.message,
            "libraryCount": len(self.library),
        }

    def tracks_view(self, playlist: str) -> list:
        return [self._track_view(t) for t in self.tracks_of(playlist)]

    def mpris_state(self) -> dict:
        track = self.order[self.index] if self.current and 0 <= self.index < len(self.order) else None
        return {"playing": self.playing, "loaded": bool(track), "track": self._track_view(track),
                "position": self.position(), "index": self.index}


class Api(BaseHTTPRequestHandler):
    """The player's own door, on localhost only: the `doppler` command and the window use it."""
    protocol_version = "HTTP/1.1"
    player: Player = None

    def log_message(self, *args):
        pass

    def _json(self, data, status: int = 200):
        body = json.dumps(data, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        try:
            data = json.loads(self.rfile.read(length)) if length else {}
        except ValueError:
            data = {}
        return data if isinstance(data, dict) else {}

    def do_GET(self):
        url = urlparse(self.path)
        query = {k: v[0] for k, v in parse_qs(url.query).items()}
        player = self.player
        if url.path == "/state":
            return self._json(player.call(player.view))
        if url.path == "/wait":
            rev = int(query.get("rev", 0))
            deadline = time.monotonic() + min(float(query.get("timeout", 50)), 55)
            with player.ui_changed:
                while player.ui_rev <= rev:
                    left = deadline - time.monotonic()
                    if left <= 0:
                        break
                    player.ui_changed.wait(left)
            return self._json(player.call(player.view))
        if url.path == "/tracks":
            return self._json(player.call(player.tracks_view, unquote(query.get("playlist", "f:"))))
        self._json({"error": "not found"}, 404)

    def do_POST(self):
        path = urlparse(self.path).path
        body = self._body()
        player = self.player
        simple = {"/pause": player.pause, "/resume": player.resume, "/toggle": player.toggle}
        try:
            if path in simple:
                return self._json(player.call(simple[path]))
            if path == "/slot":
                return self._json(player.call(player.slot, str(body.get("slot", "1"))))
            if path == "/play":
                return self._json(player.call(player.play, str(body.get("playlist", "")), body.get("track"),
                                              bool(body.get("fresh"))))
            if path == "/next":
                return self._json(player.call(player.step, 1))
            if path == "/prev":
                return self._json(player.call(player.step, -1))
            if path == "/seek":
                return self._json(player.call(player.seek, float(body.get("position", 0))))
            if path == "/hub":
                # Pass playlist, slot and mode edits straight through to the hub.
                reply = player.hub.request(str(body.get("method", "POST")), str(body.get("path", "")), body.get("body"))
                return self._json(reply)
        except OSError as error:
            return self._json({"ok": False, "error": f"can't reach the hub: {error}"})
        except RuntimeError as error:
            return self._json({"ok": False, "error": str(error)})
        self._json({"error": "not found"}, 404)


def run(mpv_extra=()):
    config = load_config()
    runtime = os.path.join(os.environ.get("XDG_RUNTIME_DIR") or "/tmp", "doppler")
    state = os.path.join(os.environ.get("XDG_STATE_HOME") or os.path.expanduser("~/.local/state"), "doppler")
    player = Player(config, state, runtime, mpv_extra)
    handler = type("BoundApi", (Api,), {"player": player})
    server = ThreadingHTTPServer(("127.0.0.1", int(config.get("port") or DEFAULT_PORT)), handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        from .mpris import Mpris
        player.mpris = Mpris(player)
    except Exception as error:  # no D-Bus here: media keys just won't reach us
        print(f"doppler: media keys are off ({error})", flush=True)
    import signal
    signal.signal(signal.SIGTERM, lambda *_: player.events.put(("quit", None)))
    signal.signal(signal.SIGINT, lambda *_: player.events.put(("quit", None)))
    print(f"doppler player: {config['device']} -> {config['hub'] or 'no hub'}", flush=True)
    try:
        player.run()
    finally:
        player.mpv.stop()
