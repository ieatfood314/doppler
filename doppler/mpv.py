"""mpv as the thing that makes sound, driven over its JSON socket.

It is started with no config of its own and nothing between the file and the output: no
resampling, no volume scaling, no normalisation. What the file holds is what PipeWire gets."""

from __future__ import annotations

import json
import os
import socket
import subprocess
import threading
import time

OBSERVED = ("playlist-pos", "pause", "idle-active")


def arguments(socket_path: str, extra=()) -> list[str]:
    return [
        "mpv", "--no-config", "--idle=yes", "--no-terminal", "--no-video", "--audio-display=no",
        "--force-window=no", "--input-default-bindings=no", "--input-media-keys=no", "--no-input-cursor",
        "--input-ipc-server=" + socket_path,
        # Lossless stays lossless: unity gain, no ReplayGain, no filters, native rate and depth.
        "--volume=100", "--volume-max=100", "--replaygain=no", "--af=", "--audio-pitch-correction=no",
        "--audio-normalize-downmix=no", "--audio-channels=auto-safe",
        "--gapless-audio=weak", "--prefetch-playlist=yes",
        "--audio-client-name=doppler", "--title=doppler",
        "--keep-open=no", "--loop-playlist=no", "--load-scripts=no", "--ytdl=no", "--osc=no",
        "--network-timeout=15", "--demuxer-max-bytes=24MiB", "--demuxer-max-back-bytes=4MiB",
        *extra,
    ]


class Mpv:
    def __init__(self, socket_path: str, on_event, extra=()):
        self.socket_path = socket_path
        self.on_event = on_event
        self.extra = tuple(extra)
        self.process = None
        self.sock = None
        self._lock = threading.Lock()
        self._replies: dict[int, dict] = {}
        self._waiting: dict[int, threading.Event] = {}
        self._next_id = 1

    def alive(self) -> bool:
        return self.process is not None and self.process.poll() is None and self.sock is not None

    def start(self):
        if self.alive():
            return
        self.stop()
        try:
            os.unlink(self.socket_path)
        except OSError:
            pass
        self.process = subprocess.Popen(arguments(self.socket_path, self.extra), stdin=subprocess.DEVNULL,
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + 5
        while True:
            try:
                sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                sock.connect(self.socket_path)
                break
            except OSError:
                sock.close()
                if time.monotonic() > deadline or self.process.poll() is not None:
                    self.stop()
                    raise RuntimeError("mpv did not start")
                time.sleep(0.03)
        self.sock = sock
        threading.Thread(target=self._read, args=(sock,), daemon=True).start()
        for number, name in enumerate(OBSERVED, 1):
            self.command("observe_property", number, name)

    def stop(self):
        sock, process = self.sock, self.process
        self.sock = self.process = None
        if sock is not None:
            try:
                sock.sendall(b'{"command":["quit"]}\n')
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass
        if process is not None:
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

    def _read(self, sock):
        buffer = b""
        while True:
            try:
                chunk = sock.recv(65536)
            except OSError:
                chunk = b""
            if not chunk:
                break
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                try:
                    message = json.loads(line)
                except ValueError:
                    continue
                if "request_id" in message and "event" not in message:
                    waiter = self._waiting.pop(message["request_id"], None)
                    if waiter:
                        self._replies[message["request_id"]] = message
                        waiter.set()
                elif "event" in message:
                    self.on_event(message)
        if self.sock is sock:
            self.on_event({"event": "doppler-mpv-gone"})

    def command(self, *args, timeout: float = 3.0):
        """Run one mpv command and return its data. Raises RuntimeError if mpv says no or is gone."""
        sock = self.sock
        if sock is None:
            raise RuntimeError("mpv is not running")
        with self._lock:
            request = self._next_id
            self._next_id += 1
            waiter = threading.Event()
            self._waiting[request] = waiter
            try:
                sock.sendall(json.dumps({"command": list(args), "request_id": request}).encode() + b"\n")
            except OSError as error:
                self._waiting.pop(request, None)
                raise RuntimeError(f"mpv is gone: {error}")
        if not waiter.wait(timeout):
            self._waiting.pop(request, None)
            raise RuntimeError(f"mpv did not answer {args[0]}")
        reply = self._replies.pop(request)
        if reply.get("error") != "success":
            raise RuntimeError(f"mpv: {args}: {reply.get('error')}")
        return reply.get("data")

    def get(self, name: str, default=None):
        try:
            return self.command("get_property", name)
        except RuntimeError:
            return default

    def set(self, name: str, value):
        self.command("set_property", name, value)
