"""Media keys and the desktop's media controls, through MPRIS. Needs python-dbus and
python-gobject; without them the player runs the same, minus the keys."""

from __future__ import annotations

import os
import subprocess
import sys
import threading

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

ROOT = "org.mpris.MediaPlayer2"
PLAYER = "org.mpris.MediaPlayer2.Player"
PROPS = "org.freedesktop.DBus.Properties"
PATH = "/org/mpris/MediaPlayer2"


class Mpris(dbus.service.Object):
    def __init__(self, player):
        dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
        bus = dbus.SessionBus()
        self.bus_name = dbus.service.BusName("org.mpris.MediaPlayer2.doppler", bus)
        super().__init__(bus, PATH)
        self.player = player
        self.state = {"playing": False, "loaded": False, "track": None, "position": 0.0, "index": 0}
        threading.Thread(target=GLib.MainLoop().run, daemon=True).start()

    # The player thread calls this; the bus work happens on the GLib thread.
    def update(self, state: dict):
        GLib.idle_add(self._apply, state)

    def _apply(self, state: dict):
        old, self.state = self.state, state
        changed = {}
        if old["playing"] != state["playing"] or old["loaded"] != state["loaded"]:
            changed["PlaybackStatus"] = self._status()
        if (old["track"] or {}).get("id") != (state["track"] or {}).get("id"):
            changed["Metadata"] = self._metadata()
        if changed:
            self.PropertiesChanged(PLAYER, changed, [])
        return False

    def _status(self) -> str:
        if self.state["playing"]:
            return "Playing"
        return "Paused" if self.state["loaded"] else "Stopped"

    def _metadata(self):
        track = self.state["track"]
        if not track:
            return dbus.Dictionary({"mpris:trackid": dbus.ObjectPath(PATH + "/TrackList/NoTrack")}, signature="sv")
        data = {
            "mpris:trackid": dbus.ObjectPath(f"{PATH}/track/{self.state['index']}"),
            "xesam:title": track["title"],
            "xesam:artist": dbus.Array([track["artist"]] if track["artist"] else [], signature="s"),
            "xesam:album": track["album"],
            "mpris:length": dbus.Int64(int(float(track["duration"] or 0) * 1e6)),
        }
        if track.get("art"):
            data["mpris:artUrl"] = track["art"]
        return dbus.Dictionary(data, signature="sv")

    def _do(self, function, *args):
        # Never block the bus on the player.
        threading.Thread(target=self.player.call, args=(function, *args), daemon=True).start()

    def _all(self, interface: str):
        if interface == ROOT:
            return {"CanQuit": False, "CanRaise": True, "HasTrackList": False, "Identity": "doppler",
                    "DesktopEntry": "doppler", "SupportedUriSchemes": dbus.Array([], signature="s"),
                    "SupportedMimeTypes": dbus.Array([], signature="s")}
        if interface == PLAYER:
            return {"PlaybackStatus": self._status(), "LoopStatus": "Playlist", "Rate": 1.0, "Shuffle": True,
                    "Metadata": self._metadata(), "Volume": 1.0,
                    "Position": dbus.Int64(int(self.player.call(self.player.position, timeout=1.0) * 1e6)
                                           if self.state["loaded"] else 0),
                    "MinimumRate": 1.0, "MaximumRate": 1.0, "CanGoNext": True, "CanGoPrevious": True,
                    "CanPlay": True, "CanPause": True, "CanSeek": True, "CanControl": True}
        return {}

    @dbus.service.method(PROPS, in_signature="ss", out_signature="v")
    def Get(self, interface, name):
        return self._all(interface)[name]

    @dbus.service.method(PROPS, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        return self._all(interface)

    @dbus.service.method(PROPS, in_signature="ssv")
    def Set(self, interface, name, value):
        pass  # volume belongs to the system mixer; doppler never scales samples

    @dbus.service.signal(PROPS, signature="sa{sv}as")
    def PropertiesChanged(self, interface, changed, invalidated):
        pass

    @dbus.service.method(ROOT)
    def Raise(self):
        here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        subprocess.Popen([sys.executable, os.path.join(here, "bin", "doppler"), "ui"], start_new_session=True)

    @dbus.service.method(ROOT)
    def Quit(self):
        pass

    @dbus.service.method(PLAYER)
    def Play(self):
        self._do(self.player.resume)

    @dbus.service.method(PLAYER)
    def Pause(self):
        self._do(self.player.pause)

    @dbus.service.method(PLAYER)
    def PlayPause(self):
        self._do(self.player.toggle)

    @dbus.service.method(PLAYER)
    def Stop(self):
        self._do(self.player.pause)

    @dbus.service.method(PLAYER)
    def Next(self):
        self._do(self.player.step, 1)

    @dbus.service.method(PLAYER)
    def Previous(self):
        self._do(self.player.step, -1)

    @dbus.service.method(PLAYER, in_signature="x")
    def Seek(self, offset):
        self._do(lambda: self.player.seek(self.player.position() + offset / 1e6))

    @dbus.service.method(PLAYER, in_signature="ox")
    def SetPosition(self, track, position):
        self._do(self.player.seek, position / 1e6)

    @dbus.service.method(PLAYER, in_signature="s")
    def OpenUri(self, uri):
        pass

    @dbus.service.signal(PLAYER, signature="x")
    def Seeked(self, position):
        pass
