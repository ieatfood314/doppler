#!/bin/sh
# Install doppler on this Linux computer. Run it as yourself; it never needs sudo.
#
#   ./install.sh --serve FOLDER          run the hub here, for the music in FOLDER
#   ./install.sh --hub URL               run the player here, synced with the hub at URL
#   ./install.sh --hub URL --root FOLDER the same, playing this computer's own copy of the music
#   ./install.sh                         run the player here on its own (give it --root FOLDER)
#
# Options: --host 0.0.0.0 and --port 4635 for the hub, --device NAME for the player.
# Running it again updates the install.
set -eu

die() { printf 'doppler: %s\n' "$*" >&2; exit 1; }
say() { printf '%s\n' "$*"; }
usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

[ "$(id -u)" != 0 ] || die "run this as yourself, not with sudo."
here=$(cd "$(dirname "$0")" && pwd)

serve= hub= hub_given= root= device= host=127.0.0.1 port=4635 player=1
while [ $# -gt 0 ]; do
    case $1 in
        --serve) [ $# -ge 2 ] || usage; serve=$2; shift 2 ;;
        --hub) [ $# -ge 2 ] || usage; hub=$2; hub_given=1; shift 2 ;;
        --root) [ $# -ge 2 ] || usage; root=$2; shift 2 ;;
        --device) [ $# -ge 2 ] || usage; device=$2; shift 2 ;;
        --host) [ $# -ge 2 ] || usage; host=$2; shift 2 ;;
        --port) [ $# -ge 2 ] || usage; port=$2; shift 2 ;;
        *) usage ;;
    esac
done
# A hub on its own doesn't also play music unless asked to.
[ -z "$serve" ] || [ -n "$hub_given" ] || player=

command -v python3 >/dev/null || die "needs Python 3."
command -v systemctl >/dev/null || die "needs systemd to keep doppler running in the background."

units="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
bin="$HOME/.local/bin"
mkdir -p "$units" "$bin"
ln -sf "$here/bin/doppler" "$bin/doppler"

if [ -n "$serve" ]; then
    [ -d "$serve" ] || die "$serve is not a folder."
    serve=$(cd "$serve" && pwd)
    python3 -c 'import mutagen' 2>/dev/null || command -v ffprobe >/dev/null || \
        say "Note: without mutagen (python3-mutagen) or ffprobe, titles come from file names only."
    sed -e "s|@APP@|$here|g" -e "s|@ROOT@|\"$serve\"|g" -e "s|@HOST@|$host|g" -e "s|@PORT@|$port|g" \
        "$here/systemd/doppler-hub.service" > "$units/doppler-hub.service"
    systemctl --user daemon-reload
    systemctl --user enable -q doppler-hub.service
    systemctl --user restart doppler-hub.service
    say "The hub is running: http://$host:$port"
    say "It listens on this computer only unless you gave --host; put it on your tailnet or LAN for other devices."
fi

if [ -n "$player" ]; then
    command -v mpv >/dev/null || die "the player needs mpv."
    python3 -c 'import dbus, gi' 2>/dev/null || \
        say "Note: media keys need python-dbus and python-gobject; everything else works without them."
    python3 - "$here" "$hub_given" "$hub" "$root" "$device" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
from doppler.player import load_config, save_config
config = load_config()
_, _, hub_given, hub, root, device = sys.argv
if hub_given:
    config["hub"] = hub.rstrip("/")
if root:
    config["root"] = root
if device:
    config["device"] = device
save_config(config)
print(f"Player: this computer is {config['device']!r}, hub {config['hub'] or 'none'}, music {config['root'] or 'from the hub'}")
PY
    sed -e "s|@APP@|$here|g" "$here/systemd/doppler.service" > "$units/doppler.service"
    apps="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
    icons="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/scalable/apps"
    mkdir -p "$apps" "$icons"
    cp "$here/desktop/doppler.svg" "$icons/doppler.svg"
    sed -e "s|@BIN@|$bin/doppler|g" "$here/desktop/doppler.desktop" > "$apps/doppler.desktop"
    systemctl --user daemon-reload
    systemctl --user enable -q doppler.service
    systemctl --user restart doppler.service
    say "The player is running in the background. Bind two keys to:  doppler 1   and   doppler 2"
    say "The window is in your app launcher as Doppler, or: doppler ui"
fi

case ":$PATH:" in
    *":$bin:"*) ;;
    *) say "Add $bin to your PATH to use the doppler command." ;;
esac
say "Done. Update any time with: $here/update.sh"
