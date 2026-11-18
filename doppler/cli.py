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
  doppler play NAME        play a folder, artist, album or playlist you made, by name
  doppler status           what's playing, here and elsewhere
  doppler listening        how much you listened each month (add 2026-11 for one month)
  doppler wrapped          your year in music (add 2025 for another year)
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


def hours(seconds: float) -> str:
    minutes = int(seconds or 0) // 60
    return f"{minutes // 60} h {minutes % 60:02d} m" if minutes >= 60 else f"{minutes} m"


def month_name(month: str) -> str:
    return time.strftime("%B %Y", time.strptime(month, "%Y-%m"))


def listening(month: str | None = None):
    reply = ask("/listening")
    months = reply.get("months") or []
    if not months:
        print("Nothing heard yet")
        return
    if reply.get("local"):
        print("hub unreachable: this computer's own listening only\n")
    width = max(len(month_name(m["month"])) for m in months[:12])
    for m in months[:12]:
        print(f"{month_name(m['month']):<{width}}  {hours(m['seconds']):>10}  {m['plays']} plays")
    pick = next((m for m in months if m["month"] == month), None) if month else months[0]
    if month and not pick:
        sys.exit(f"doppler: nothing heard in {month}")
    print(f"\n{month_name(pick['month'])}: {hours(pick['seconds'])}, {pick['songs']} different songs")
    if pick["artists"]:
        print("  artists: " + ", ".join(f"{name} ({hours(s)})" for name, s in pick["artists"]))
    for t in pick["top"]:
        print(f"  {hours(t['seconds']):>10}  {t['artist'] + ' - ' if t['artist'] else ''}{t['title']}")


def wrapped_year(today=None) -> int:
    """The year the story is about: this one in December, last one in January, else this one so far."""
    today = today or time.localtime()
    return today.tm_year - 1 if today.tm_mon == 1 else today.tm_year


def wrapped(year: str | None = None):
    if year is not None and not (year.isdigit() and len(year) == 4):
        sys.exit("doppler: give a year, like 2026")
    year = int(year) if year else wrapped_year()
    w = ask(f"/wrapped?year={year}")
    if not w.get("seconds"):
        print(f"Nothing heard in {year}")
        return
    if w.get("local"):
        print("hub unreachable: this computer's own listening only\n")
    best = max(range(12), key=lambda i: w["months"][i])
    month = time.strftime("%B", time.strptime(str(best + 1), "%m"))
    print(f"Your {year} in music\n")
    print(f"  {w['seconds'] // 60:,} minutes, the most in {month}")
    print(f"  {w['songs']:,} different songs by {w['artistCount']:,} artists\n")
    print("  Top artists")
    for i, (name, seconds, _) in enumerate(w["artists"], 1):
        print(f"  {i}  {name}  ({hours(seconds)})")
    print("\n  Top songs")
    for i, t in enumerate(w["top"], 1):
        print(f"  {i}  {t['artist'] + ' - ' if t['artist'] else ''}{t['title']}  ({t['plays']} plays, {hours(t['seconds'])})")


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
    elif command in ("listening", "stats"):
        listening(rest[0] if rest else None)
    elif command == "wrapped":
        wrapped(rest[0] if rest else None)
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
