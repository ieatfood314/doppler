# doppler

Two shuffle playlists on two keys, synced between computers and a phone. The README says what it does; keep it true when behaviour changes.

## Map

- `doppler/core.py`: the pure rules: playlist ids (`f:<folder path>`, `c:<hex>`), shuffles (`new_order`, `order_hash`), `reconcile` (fitting a saved shuffle to a folder that changed), `live_position` and `resume_point`. `android/.../Core.kt` is a port of it; change both together. `CoreTest.kt` checks the hash against Python's.
- `doppler/library.py`: walks the music folder, reads tags (mutagen, then ffprobe, then the file name), caches them, and serves art. `path()` refuses anything outside the library.
- `doppler/hub.py`: the hub (stdlib `http.server`): library, files with Range, art, sessions, custom playlists, slots, and the `/api/wait` long poll. State is in `~/.local/share/doppler/hub.json`.
- `doppler/player.py`: the desktop daemon. One event thread owns all state, a sender thread reports to the hub, and a watcher long-polls it. It has a local API on `127.0.0.1:4636`, which the CLI and the window use. `mpv.py` is the JSON IPC client, and `mpris.py` provides the media keys.
- `doppler/cli.py`: the `doppler` command. `desktop/Main.qml` is the window, run with `qml6`; it exists only while open.
- `android/`: Kotlin, Compose, Media3. `Engine` is the singleton that owns the player and sync. `PlaybackService` keeps it alive after the task is swiped away. `Speaker` and the `Gate` player are the speaker question. `Widgets.kt` holds the two widgets, and `ConfirmActivity` is their invisible tap target.
- The numerals are drawn, not typeset: a 5 by 7 grid of stars defined three times, in `Dots.kt`, the `DotNumeral` component in `desktop/Main.qml` and `tools/make_art.py` (which writes the widget vectors, the icons and `desktop/doppler.svg`). Change all three together.
- `install.sh`, `update.sh`, `systemd/`: per-user install, no sudo.

## Rules that are easy to break

- **Audio must stay bit-exact.** mpv runs with `--no-config`, volume 100 capped at 100, no ReplayGain, no filters and no pitch correction. ExoPlayer uses float output (keeps 24-bit), with no skip-silence and no normalisation. Never add processing to the signal path; EQ is EasyEffects' job, chosen per playlist.
- **One device plays.** A report with `claim` makes the sender the owner. Non-claim reports from anyone else are answered `superseded`, and that device pauses. Claims come from a press of play (or a playing device reshuffling at the end); heartbeats and track changes never claim. A claim queued offline carries its `age`, so a newer claim elsewhere wins.
- **Reports must be safe to send twice and out of order.** Players queue them while offline and replay them later.
- **The phone never plays from its own speaker without asking**, including from the notification, a headset button or a widget. A yes lasts 10 minutes and is reset when the output changes.
- **The service owns the notification.** `PlaybackService` hands its session to Media3 with `addSession` in `onCreate`, since the app drives ExoPlayer directly and no controller ever asks for it. Without that there's no media notification, no foreground service, and the music dies when the app is swiped away.
- **The desktop window is optional.** Nothing in the player may depend on it, and it must cost nothing when closed.

## Test

```sh
python3 -m unittest discover -s tests -q
```

```sh
cd android && ./build.sh
```

`build.sh` runs the Kotlin unit tests and builds the signed APK. It needs the toolchain in `~/.local/android-toolchain` and the key in `~/.config/doppler/` (`keystore.properties` and `release.jks`; losing the key means no more in-place updates). `android.properties` there sets the default hub and phone name that get baked into the build.

To try the player without touching a real session, run a throwaway hub with its own `XDG_DATA_HOME` and `XDG_CACHE_HOME` (`bin/doppler serve FOLDER --port 4699`; without them it reads and writes the real hub's `hub.json`), and a player with its own `XDG_CONFIG_HOME` and `XDG_STATE_HOME`, plus `daemon --mpv=--ao=null`. Release builds default to the real hub, so point a test phone or emulator at the throwaway one in Settings before pressing play.

## Ship

1. Run the tests, commit, then push to `main` (public: `github.com/ieatfood314/doppler`).
2. The hub runs from this checkout as the user unit `doppler-hub.service`. After a Python change, run `systemctl --user restart doppler-hub`.
3. Computers run a clone in `~/.local/share/doppler/app`. Run `update.sh` there after a player or window change.
4. After a change under `android/`, run `android/release.sh "what changed"`. It builds from the pushed commit and creates the GitHub release `v1.0.<commit count>`; the phone picks it up through Obtainium.
