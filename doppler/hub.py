"""The hub: one small server that knows the library and where every playlist is up to.
Devices tell it what they are playing and ask it where to pick up. It also serves the music
itself, for a device that doesn't hold a copy. No login: keep it on a private network."""

from __future__ import annotations

import gzip
import json
import mimetypes
import os
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

from . import __version__, core
from .library import Library

MIME = {
    "flac": "audio/flac", "mp3": "audio/mpeg", "m4a": "audio/mp4", "aac": "audio/aac", "ogg": "audio/ogg",
    "oga": "audio/ogg", "opus": "audio/ogg", "wav": "audio/wav", "wma": "audio/x-ms-wma", "aiff": "audio/aiff",
    "aif": "audio/aiff", "wv": "audio/x-wavpack", "ape": "audio/x-ape", "mka": "audio/x-matroska",
}


class Hub:
    """Everything the hub knows, behind one lock. The HTTP layer below only translates."""

    def __init__(self, data_dir: str, library: Library | None = None, clock=time.time):
        self.clock = clock
        self.library = library
        self.path = os.path.join(data_dir, "hub.json")
        os.makedirs(data_dir, exist_ok=True)
        self.changed = threading.Condition()
        self.state = {"rev": 1, "active": None, "sessions": {}, "slots": {}, "playlists": {}, "modes": {}, "devices": {}}
        try:
            with open(self.path) as handle:
                self.state.update(json.load(handle))
        except (OSError, ValueError):
            pass
        self._saved_at = 0.0
        self._dirty = False

    # --- persistence

    def _save(self, now: bool = True):
        """Structural changes are written at once; position reports at most every few seconds."""
        self._dirty = True
        if not now and self.clock() - self._saved_at < 5:
            return
        tmp = self.path + ".tmp"
        with open(tmp, "w") as handle:
            json.dump(self.state, handle)
        os.replace(tmp, self.path)
        self._saved_at = self.clock()
        self._dirty = False

    def flush(self):
        with self.changed:
            if self._dirty:
                self._save()

    def _bump(self):
        self.state["rev"] += 1
        self.changed.notify_all()

    # --- reading

    def _track_ids(self):
        return self.library.ids() if self.library else []

    def slots(self) -> dict:
        slots = dict(core.default_slots(set(core.folders(self._track_ids()))))
        slots.update({k: v for k, v in self.state["slots"].items() if v})
        return slots

    def public(self) -> dict:
        with self.changed:
            sessions = {}
            for playlist, s in self.state["sessions"].items():
                order = s["order"]
                sessions[playlist] = {
                    "playlist": playlist, "index": s["index"], "position": s["position"], "playing": s["playing"],
                    "device": s["device"], "updated": s["updated"], "orderHash": s["orderHash"],
                    "count": len(order), "track": order[s["index"]] if 0 <= s["index"] < len(order) else None,
                }
            return {
                "rev": self.state["rev"], "now": self.clock(), "version": __version__,
                "active": self.state["active"], "sessions": sessions, "slots": self.slots(),
                "playlists": sorted(self.state["playlists"].values(), key=lambda p: core.natural_key(p["name"])),
                "modes": self.state["modes"], "devices": self.state["devices"],
                "library": {"rev": self.library.rev if self.library else "", "count": len(self._track_ids())},
            }

    def wait(self, rev: int, timeout: float) -> dict:
        deadline = time.monotonic() + timeout
        with self.changed:
            while self.state["rev"] <= rev:
                left = deadline - time.monotonic()
                if left <= 0:
                    break
                self.changed.wait(left)
        return self.public()

    def session(self, playlist: str, have: str | None = None) -> dict:
        with self.changed:
            s = self.state["sessions"].get(playlist)
            out = {"now": self.clock(), "session": None, "mode": self.state["modes"].get(playlist, "shuffle")}
            if s:
                out["session"] = dict(s)
                if have and have == s["orderHash"]:
                    del out["session"]["order"]
            return out

    # --- writing

    def report(self, body: dict) -> dict:
        """A device saying where it is. `claim` means someone just pressed play there, which makes
        it the one device that is playing; everything else is a device keeping its own session
        current, and is turned away once another device has taken over."""
        device = str(body.get("device") or "").strip()
        playlist = str(body.get("playlist") or "")
        if not device or not playlist:
            return {"ok": False, "error": "device and playlist are required"}
        now = self.clock()
        at = now - max(0.0, float(body.get("age") or 0))
        claim = bool(body.get("claim"))
        playing = bool(body.get("playing"))
        with self.changed:
            self.state["devices"][device] = {"seen": now}
            s = self.state["sessions"].get(playlist)
            active = self.state["active"]
            other_active = bool(active and active["device"] != device)
            if claim:
                if s and s["device"] != device and at < s["updated"] - 1:
                    return {"ok": False, "stale": True, "superseded": playing and other_active, "active": active}
                if playing and other_active and at < active["since"]:
                    return {"ok": False, "superseded": True, "active": active}
            else:
                if (s and s["device"] != device) or (playing and other_active):
                    return {"ok": False, "superseded": True, "active": active}
            order = body.get("order")
            if order is None:
                if not s or s["orderHash"] != body.get("orderHash"):
                    return {"ok": False, "needOrder": True}
                order = s["order"]
            else:
                order = [str(t) for t in order]
            index = core.clamp_index(body.get("index"), len(order))
            new = {
                "playlist": playlist, "order": order, "orderHash": core.order_hash(order), "index": index,
                "position": max(0.0, float(body.get("position") or 0)), "playing": playing,
                "device": device, "updated": at,
            }
            visible = (not s or claim or s["index"] != index or s["playing"] != playing
                       or s["orderHash"] != new["orderHash"] or s["device"] != device)
            self.state["sessions"][playlist] = new
            if (claim or playing) and (not active or at >= active["since"] or active["device"] == device):
                if not active or active["device"] != device or active["playlist"] != playlist:
                    self.state["active"] = {"device": device, "playlist": playlist, "since": at}
                    visible = True
            if visible:
                self._bump()
            self._save(now=visible)
            return {"ok": True, "rev": self.state["rev"], "now": now}

    def put_playlist(self, playlist: str | None, body: dict) -> dict:
        with self.changed:
            if not playlist:
                playlist = "c:" + secrets.token_hex(4)
            elif not playlist.startswith("c:"):
                return {"ok": False, "error": "only hand-made playlists can be edited; folders are their files"}
            entry = self.state["playlists"].get(playlist, {"id": playlist, "name": "Untitled", "tracks": []})
            if "name" in body:
                entry["name"] = str(body["name"]).strip()[:120] or "Untitled"
            if "tracks" in body:
                seen, tracks = set(), []
                for track in body["tracks"]:
                    if track not in seen:
                        seen.add(track)
                        tracks.append(str(track))
                entry["tracks"] = tracks
            for track in body.get("add", []):
                if track not in entry["tracks"]:
                    entry["tracks"].append(str(track))
            if body.get("remove"):
                gone = set(body["remove"])
                entry["tracks"] = [t for t in entry["tracks"] if t not in gone]
            entry["updated"] = self.clock()
            self.state["playlists"][playlist] = entry
            self._bump()
            self._save()
            return {"ok": True, "playlist": entry}

    def delete_playlist(self, playlist: str) -> dict:
        with self.changed:
            if self.state["playlists"].pop(playlist, None) is None:
                return {"ok": False, "error": "no such playlist"}
            self.state["sessions"].pop(playlist, None)
            self.state["modes"].pop(playlist, None)
            for slot, held in list(self.state["slots"].items()):
                if held == playlist:
                    del self.state["slots"][slot]
            self._bump()
            self._save()
            return {"ok": True}

    def set_slots(self, body: dict) -> dict:
        with self.changed:
            for slot in ("1", "2"):
                if slot in body:
                    if body[slot]:
                        self.state["slots"][slot] = str(body[slot])
                    else:
                        self.state["slots"].pop(slot, None)
            self._bump()
            self._save()
        return {"ok": True, "slots": self.slots()}

    def set_mode(self, body: dict) -> dict:
        playlist, mode = str(body.get("playlist") or ""), body.get("mode")
        if not playlist or mode not in ("shuffle", "order"):
            return {"ok": False, "error": "playlist and mode (shuffle or order) are required"}
        with self.changed:
            if mode == "shuffle":
                self.state["modes"].pop(playlist, None)
            else:
                self.state["modes"][playlist] = mode
            self._bump()
            self._save()
        return {"ok": True}

    def library_changed(self):
        with self.changed:
            self._bump()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "doppler/" + __version__
    hub: Hub = None  # set by serve()
    web: str = ""

    def log_message(self, *args):
        pass

    # --- plumbing

    def _send(self, status: int, body: bytes, kind: str, extra: dict | None = None):
        if len(body) > 1024 and "gzip" in self.headers.get("Accept-Encoding", "") and kind.startswith(("application/json", "text/")):
            body = gzip.compress(body, 5)
            extra = dict(extra or {}, **{"Content-Encoding": "gzip"})
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, data, status: int | None = None, extra: dict | None = None):
        if status is None:
            status = 400 if isinstance(data, dict) and data.get("error") else 200
        self._send(status, json.dumps(data, separators=(",", ":")).encode(), "application/json", extra)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            data = json.loads(self.rfile.read(length))
        except ValueError:
            return {}
        return data if isinstance(data, dict) else {}

    def _file(self, path: str, kind: str):
        size = os.path.getsize(path)
        start, end, status = 0, size - 1, 200
        spec = self.headers.get("Range", "")
        if spec.startswith("bytes="):
            first, _, last = spec[6:].split(",")[0].partition("-")
            try:
                if first:
                    start = int(first)
                    end = min(int(last), size - 1) if last else size - 1
                else:
                    start = max(0, size - int(last))
                status = 206
            except ValueError:
                status = 200
            if status == 206 and (start > end or start >= size):
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Access-Control-Allow-Origin", "*")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(path, "rb") as handle:
            handle.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = handle.read(min(256 * 1024, left))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                left -= len(chunk)

    # --- routes

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urlparse(self.path)
        query = {k: v[0] for k, v in parse_qs(url.query).items()}
        path = unquote(url.path)
        hub, library = self.hub, self.hub.library
        if path in ("/api/state", "/api/wait", "/api/library") and library and library.scan():
            hub.library_changed()
        if path == "/api/state":
            return self._json(hub.public())
        if path == "/api/wait":
            timeout = min(max(float(query.get("timeout", 50)), 0), 55)
            return self._json(hub.wait(int(query.get("rev", 0)), timeout))
        if path == "/api/session":
            return self._json(hub.session(query.get("playlist", ""), query.get("have")))
        if path == "/api/library":
            tag = f'"{library.rev}"'
            if self.headers.get("If-None-Match") == tag:
                self.send_response(304)
                self.send_header("ETag", tag)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            return self._json(library.public(), extra={"ETag": tag})
        if path.startswith("/file/"):
            track = path[6:]
            full = library.path(track)
            if not full or not core.is_audio(full):
                return self._json({"error": "no such track"}, 404)
            return self._file(full, MIME.get(full.rsplit(".", 1)[-1].lower(), "application/octet-stream"))
        if path.startswith("/art/"):
            try:
                size = int(query.get("s", 640))
            except ValueError:
                size = 640
            data = library.art(path[5:], size)
            if data is None:
                return self._json({"error": "no art"}, 404)
            kind = "image/png" if data[:4] == b"\x89PNG" else "image/jpeg"
            return self._send(200, data, kind, {"Cache-Control": "max-age=86400"})
        if self.web:
            name = "index.html" if path == "/" else path.lstrip("/")
            full = os.path.realpath(os.path.join(self.web, name))
            if full.startswith(os.path.realpath(self.web) + os.sep) and os.path.isfile(full):
                kind = mimetypes.guess_type(full)[0] or "application/octet-stream"
                with open(full, "rb") as handle:
                    return self._send(200, handle.read(), kind)
        self._json({"error": "not found"}, 404)

    def do_POST(self):
        path = unquote(urlparse(self.path).path)
        body = self._body()
        if path == "/api/session":
            return self._json(self.hub.report(body), 200)
        if path == "/api/playlists":
            return self._json(self.hub.put_playlist(None, body))
        if path == "/api/rescan":
            if self.hub.library.scan(force=True):
                self.hub.library_changed()
            return self._json({"ok": True, "count": len(self.hub.library.ids())})
        self._json({"error": "not found"}, 404)

    def do_PUT(self):
        path = unquote(urlparse(self.path).path)
        body = self._body()
        if path.startswith("/api/playlists/"):
            return self._json(self.hub.put_playlist(path[15:], body))
        if path == "/api/slots":
            return self._json(self.hub.set_slots(body))
        if path == "/api/mode":
            return self._json(self.hub.set_mode(body))
        self._json({"error": "not found"}, 404)

    def do_DELETE(self):
        path = unquote(urlparse(self.path).path)
        if path.startswith("/api/playlists/"):
            return self._json(self.hub.delete_playlist(path[15:]))
        self._json({"error": "not found"}, 404)


def make_server(hub: Hub, host: str, port: int, web: str = "") -> ThreadingHTTPServer:
    handler = type("BoundHandler", (Handler,), {"hub": hub, "web": web})
    server = ThreadingHTTPServer((host, port), handler)
    server.daemon_threads = True
    return server


def serve(root: str, host: str, port: int, data_dir: str, cache_dir: str):
    library = Library(root, cache_dir)
    hub = Hub(data_dir, library)

    def first_scan():
        if library.scan(force=True):
            hub.library_changed()

    threading.Thread(target=first_scan, daemon=True).start()
    web = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "web")
    server = make_server(hub, host, port, web if os.path.isdir(web) else "")

    def keep_fresh():
        while True:
            time.sleep(60)
            try:
                if library.scan():
                    hub.library_changed()
                hub.flush()
            except Exception:
                pass

    threading.Thread(target=keep_fresh, daemon=True).start()
    print(f"doppler hub: {root} on http://{host}:{port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        hub.flush()
