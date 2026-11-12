"""The `doppler` command."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

from . import __version__

HELP = """doppler: two playlists, one keypress, the same place on every device

  doppler 1 | 2            play that playlist where it was left, or pause it if it's playing
  doppler toggle           play or pause
  doppler next | prev      skip
  doppler play NAME        play a folder (or a playlist you made) by name
  doppler status           what's playing, here and elsewhere
  doppler ui               open the window
  doppler connect URL      point this computer's player at a hub
  doppler daemon           run the player (the installer sets this up as a service)
  doppler serve FOLDER     run the hub for a music folder
"""


def _port() -> int:
    from .player import load_config
    return int(load_config().get("port") or 4636)


def _ask(path: str, body=None, timeout: float = 12.0):
    url = f"http://127.0.0.1:{_port()}{path}"
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method="POST" if data is not None else "GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read())


def ask(path: str, body=None):
    """Talk to the player, starting it first if it isn't running."""
    try:
        return _ask(path, body)
    except (urllib.error.URLError, ConnectionError):
        subprocess.run(["systemctl", "--user", "start", "doppler.service"], capture_output=True)
        for _ in range(40):
            time.sleep(0.1)
            try:
                return _ask(path, body)
            except (urllib.error.URLError, ConnectionError):
                continue
    sys.exit("doppler: the player isn't running. Start it with: systemctl --user start doppler")


def clock(seconds: float) -> str:
    seconds = int(seconds or 0)
    return f"{seconds // 60}:{seconds % 60:02d}"


def show(reply):
    if isinstance(reply, dict) and reply.get("ok") is False:
        sys.exit("doppler: " + reply.get("error", "that didn't work"))


def status():
    state = ask("/state")
    track = state.get("track")
    if track:
        mark = "playing" if state["playing"] else "paused"
        slot = next((k for k, v in state["slots"].items() if v == state["playlist"]), "")
        where = f"{state['playlistName']}" + (f" (key {slot})" if slot else "")
        print(f"{mark}: {track['artist'] + ' - ' if track['artist'] else ''}{track['title']}")
        print(f"  {clock(state['position'])} / {clock(track['duration'])}   track {state['index'] + 1} of {state['count']} in {where}")
    else:
        print("nothing loaded on this computer")
    other = state.get("elsewhere")
    if other and other.get("track"):
        mark = "playing" if other["playing"] else "paused"
        print(f"{other['device']} is {mark}: {other['track']['title']} ({other['name']})")
    hub = state["hub"]
    print(f"hub: {hub['url'] or 'none'}" + ("" if hub["online"] or not hub["url"] else " (unreachable)"))
    if state.get("message"):
        print(state["message"])


def play_named(name: str):
    state = ask("/state")
    wanted = name.casefold()
    exact = [p for p in state["playlists"] if wanted in (p["name"].casefold(), p["path"].casefold(), p["id"].casefold())]
    close = exact or [p for p in state["playlists"] if wanted in p["name"].casefold()]
    if not close:
        sys.exit(f"doppler: no playlist called {name!r}. Folders: " +
                 ", ".join(p["name"] for p in state["playlists"] if "/" not in p["path"] and p["path"]))
    show(ask("/play", {"playlist": close[0]["id"]}))


def open_ui():
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    runtime = os.path.join(os.environ.get("XDG_RUNTIME_DIR") or "/tmp", "doppler")
    os.makedirs(runtime, exist_ok=True)
    pidfile = os.path.join(runtime, "ui.pid")
    try:  # one window: a second launch replaces the first, so it always comes up in front
        with open(pidfile) as handle:
            old = int(handle.read())
        with open(f"/proc/{old}/cmdline", "rb") as handle:
            if b"doppler" in handle.read():
                os.kill(old, 15)
    except (OSError, ValueError):
        pass
    ask("/state")  # make sure the player is up before the window asks
    binary = next((b for b in ("qml6", "/usr/lib/qt6/bin/qml", "qml") if subprocess.run(
        ["sh", "-c", f"command -v {b}"], capture_output=True).returncode == 0), None)
    if not binary:
        sys.exit("doppler: the window needs Qt 6's qml runtime (qt6-declarative).")
    env = dict(os.environ, QT_QUICK_CONTROLS_STYLE="Basic")
    with open(pidfile, "w") as handle:
        handle.write(str(os.getpid()))
    os.execvpe(binary, [binary, "--apptype", "gui", os.path.join(here, "desktop", "Main.qml"), "--", str(_port())], env)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(HELP)
        return
    command, rest = argv[0], argv[1:]
    if command in ("1", "2"):
        show(ask("/slot", {"slot": command}))
    elif command == "slot" and rest:
        show(ask("/slot", {"slot": rest[0]}))
    elif command in ("toggle", "pause", "resume", "next", "prev"):
        show(ask("/" + command, {}))
    elif command == "play" and rest:
        play_named(" ".join(rest))
    elif command == "status":
        status()
    elif command == "ui":
        open_ui()
    elif command == "version":
        print(__version__)
    elif command == "connect" and rest:
        from .player import load_config, save_config
        config = load_config()
        config["hub"] = rest[0].rstrip("/")
        save_config(config)
        print(f"This computer ({config['device']}) now syncs with {config['hub']}")
    elif command == "daemon":
        from .player import run
        parser = argparse.ArgumentParser(prog="doppler daemon")
        parser.add_argument("--mpv", action="append", default=[], help="extra mpv option, e.g. --mpv=--ao=null")
        run(parser.parse_args(rest).mpv)
    elif command == "serve":
        from .hub import serve
        parser = argparse.ArgumentParser(prog="doppler serve")
        parser.add_argument("root", help="the music folder")
        parser.add_argument("--host", default="127.0.0.1")
        parser.add_argument("--port", type=int, default=4635)
        args = parser.parse_args(rest)
        data = os.path.join(os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share"), "doppler")
        cache = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "doppler")
        serve(args.root, args.host, args.port, data, cache)
    else:
        print(HELP)
        sys.exit(2)
