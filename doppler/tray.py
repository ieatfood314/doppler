"""The desktop tray icon. Ayatana AppIndicator speaks StatusNotifierItem to KDE and
Cinnamon; the icon belongs to the player, so it stays there after the window closes."""

from __future__ import annotations

import os
import subprocess
import sys
import threading

APP_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def action_label(state: dict) -> str:
    if state.get("playing"):
        return "Pause"
    return "Resume" if state.get("loaded") else "Play"


def track_text(state: dict) -> str:
    track = state.get("track")
    if not track:
        return "Nothing playing"
    status = "Playing" if state.get("playing") else "Paused"
    artist = f"{track['artist']} — " if track.get("artist") else ""
    return f"{status}: {artist}{track['title']}"


class Tray:
    def __init__(self, player):
        import gi
        gi.require_version("Gtk", "3.0")
        try:
            gi.require_version("AyatanaAppIndicator3", "0.1")
            from gi.repository import AyatanaAppIndicator3 as AppIndicator
        except ValueError:
            gi.require_version("AppIndicator3", "0.1")
            from gi.repository import AppIndicator3 as AppIndicator
        from gi.repository import GLib, Gtk
        self.GLib = GLib
        self.Gtk = Gtk
        self.AppIndicator = AppIndicator
        self.player = player
        self.state = player.mpris_state()
        self.icon = None
        self.track_item = None
        self.play_item = None
        self.display_warning = False
        GLib.idle_add(self._start)

    # AppIndicator and GTK objects live on MPRIS's GLib thread.
    def _start(self):
        Gtk, AppIndicator = self.Gtk, self.AppIndicator
        if not Gtk.init_check()[0]:
            if not self.display_warning:
                print("doppler: tray icon is waiting for a graphical display", flush=True)
                self.display_warning = True
            self.GLib.timeout_add_seconds(5, self._start)
            return False

        self.icon = AppIndicator.Indicator.new_with_path(
            "doppler", "doppler", AppIndicator.IndicatorCategory.APPLICATION_STATUS,
            os.path.join(APP_ROOT, "desktop"))
        self.icon.set_title("Doppler")
        self.icon.set_icon_full("doppler", "Doppler")

        menu = Gtk.Menu()
        self.track_item = Gtk.MenuItem(label="")
        self.track_item.set_sensitive(False)
        menu.append(self.track_item)
        menu.append(Gtk.SeparatorMenuItem())
        self.play_item = self._item(menu, "", lambda: self._do(self.player.toggle))
        self._item(menu, "Previous", lambda: self._do(self.player.step, -1))
        self._item(menu, "Next", lambda: self._do(self.player.step, 1))
        menu.append(Gtk.SeparatorMenuItem())
        self._item(menu, "Playlist 1", lambda: self._do(self.player.slot, "1"))
        self._item(menu, "Playlist 2", lambda: self._do(self.player.slot, "2"))
        menu.append(Gtk.SeparatorMenuItem())
        self._item(menu, "Open Doppler", self._open)
        menu.show_all()
        self.icon.set_menu(menu)
        self.icon.set_secondary_activate_target(self.play_item)
        self.icon.set_status(AppIndicator.IndicatorStatus.ACTIVE)
        self._apply(self.state)
        return False

    def _item(self, menu, label, callback):
        item = self.Gtk.MenuItem(label=label)
        item.connect("activate", lambda *_: callback())
        menu.append(item)
        return item

    def _open(self):
        subprocess.Popen([sys.executable, os.path.join(APP_ROOT, "bin", "doppler"), "ui"], start_new_session=True)

    def _do(self, function, *args):
        # A slow hub or a busy player must never hold up the desktop's menu.
        threading.Thread(target=self.player.call, args=(function, *args), daemon=True).start()

    def update(self, state: dict):
        self.GLib.idle_add(self._apply, state)

    def _apply(self, state: dict):
        self.state = state
        if self.track_item:
            self.track_item.set_label(track_text(state))
        if self.play_item:
            self.play_item.set_label(action_label(state))
        return False
