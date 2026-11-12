#!/bin/sh
# Bring this install up to date. The hub restarts at once; the player restarts only when it
# isn't playing, so an update never cuts the music (it picks the new code up next time).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
git -C "$here" pull -q --ff-only
if systemctl --user is-enabled -q doppler-hub.service 2>/dev/null; then
    systemctl --user restart doppler-hub.service && echo "Restarted the hub"
fi
if systemctl --user is-enabled -q doppler.service 2>/dev/null; then
    if "$here/bin/doppler" status 2>/dev/null | grep -q '^playing:'; then
        echo "Music is playing, so the player keeps running the old code until: systemctl --user restart doppler"
    else
        systemctl --user restart doppler.service && echo "Restarted the player"
    fi
fi
echo "doppler $("$here/bin/doppler" version)"
